#define MyAppName "TranscribeMate"
#define MyAppVersion "26.02.16.014"
#define MyAppPublisher "Martin Nebehay"
#define MyAppExeName "TranscribeMate.exe"

[Setup]
; Keep AppId stable across versions so setup performs an upgrade
AppId={{3D3C5C5E-24D9-4F4A-A6A2-6E5D4A7A9C11}}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}

; Per-user install (no admin rights required)
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
DefaultDirName={localappdata}\Programs\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
UsePreviousAppDir=yes
UsePreviousGroup=yes
UsePreviousTasks=no

LicenseFile=..\LICENSE
OutputDir=..\dist_installer
OutputBaseFilename=TranscribeMate-Setup
Compression=lzma
SolidCompression=yes
SetupIconFile=..\icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64

; Upgrade behavior: close running app and replace files
AppMutex=TranscribeMateMutex
CloseApplications=yes
CloseApplicationsFilter={#MyAppExeName}
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"; Flags: unchecked
Name: "deps_core"; Description: "Install core dependencies now - PyTorch + FFmpeg"; GroupDescription: "Dependency setup:"; Flags: checkedonce
Name: "deps_speakers"; Description: "Install speaker dependencies now - diarization backend"; GroupDescription: "Dependency setup:"; Flags: checkedonce
Name: "deps_models"; Description: "Prefetch default models now - takes longer"; GroupDescription: "Dependency setup:"; Flags: checkedonce

[InstallDelete]
Type: files; Name: "{app}\_internal\torch-*.dist-info\*"
Type: dirifempty; Name: "{app}\_internal\torch-*.dist-info"
Type: files; Name: "{app}\_internal\torchaudio-*.dist-info\*"
Type: dirifempty; Name: "{app}\_internal\torchaudio-*.dist-info"
Type: files; Name: "{app}\_internal\torchvision-*.dist-info\*"
Type: dirifempty; Name: "{app}\_internal\torchvision-*.dist-info"
Type: files; Name: "{app}\_internal\torch\*"
Type: dirifempty; Name: "{app}\_internal\torch"
Type: files; Name: "{app}\_internal\torchgen\*"
Type: dirifempty; Name: "{app}\_internal\torchgen"
Type: files; Name: "{app}\_internal\functorch\*"
Type: dirifempty; Name: "{app}\_internal\functorch"
Type: files; Name: "{app}\_internal\torchaudio\*"
Type: dirifempty; Name: "{app}\_internal\torchaudio"
Type: files; Name: "{app}\_internal\torchvision\*"
Type: dirifempty; Name: "{app}\_internal\torchvision"

[UninstallDelete]
; Remove all per-user runtime data (logs, cache, models, runtime site-packages).
Type: filesandordirs; Name: "{localappdata}\TranscribeMate"

[Files]
Source: "..\dist\TranscribeMate\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Parameters: "--install-deps --deps-core --non-interactive"; Description: "Install core dependencies"; Flags: postinstall skipifsilent runhidden waituntilterminated; Tasks: deps_core
Filename: "{app}\{#MyAppExeName}"; Parameters: "--install-deps --deps-speakers --non-interactive"; Description: "Install speaker dependencies"; Flags: postinstall skipifsilent runhidden waituntilterminated; Tasks: deps_speakers
Filename: "{app}\{#MyAppExeName}"; Parameters: "--install-deps --deps-models --non-interactive"; Description: "Prefetch default models"; Flags: postinstall skipifsilent runhidden waituntilterminated; Tasks: deps_models
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent unchecked

[Code]
function NormalizedAppId(): string;
begin
  Result := '{#SetupSetting("AppId")}';
  StringChangeEx(Result, '{{', '{', True);
  StringChangeEx(Result, '}}', '}', True);
end;

function IsUpgradeInstall(): Boolean;
var
  UninstallKey: string;
begin
  UninstallKey :=
    'Software\Microsoft\Windows\CurrentVersion\Uninstall\' +
    NormalizedAppId() + '_is1';
  Result :=
    RegKeyExists(HKCU, UninstallKey) or
    RegKeyExists(HKLM, UninstallKey);
end;

procedure PurgeCacheKeepModels(const CacheDir: string);
var
  FindRec: TFindRec;
  Name: string;
  FullPath: string;
begin
  if not DirExists(CacheDir) then
    Exit;

  if FindFirst(CacheDir + '\*', FindRec) then
  begin
    try
      repeat
        Name := FindRec.Name;
        if (Name <> '.') and (Name <> '..') then
        begin
          FullPath := CacheDir + '\' + Name;
          if ((FindRec.Attributes and FILE_ATTRIBUTE_DIRECTORY) <> 0)
            and ((CompareText(Name, 'huggingface') = 0) or (CompareText(Name, 'whisper') = 0)) then
          begin
            Log('Keeping model cache directory: ' + FullPath);
          end
          else
          begin
            if (FindRec.Attributes and FILE_ATTRIBUTE_DIRECTORY) <> 0 then
            begin
              if not DelTree(FullPath, True, True, True) then
                Log('Could not remove runtime cache directory: ' + FullPath);
            end
            else
            begin
              if not DeleteFile(FullPath) then
                Log('Could not remove runtime cache file: ' + FullPath);
            end;
          end;
        end;
      until not FindNext(FindRec);
    finally
      FindClose(FindRec);
    end;
  end;
end;

procedure PurgeRuntimeDataKeepConfigAndModels(const DataDir: string);
var
  FindRec: TFindRec;
  Name: string;
  FullPath: string;
begin
  if not DirExists(DataDir) then
    Exit;

  if FindFirst(DataDir + '\*', FindRec) then
  begin
    try
      repeat
        Name := FindRec.Name;
        if (Name <> '.') and (Name <> '..') then
        begin
          FullPath := DataDir + '\' + Name;
          if (FindRec.Attributes and FILE_ATTRIBUTE_DIRECTORY) <> 0 then
          begin
            if CompareText(Name, 'cache') = 0 then
            begin
              PurgeCacheKeepModels(FullPath);
            end
            else
            begin
              if not DelTree(FullPath, True, True, True) then
                Log('Could not remove runtime directory: ' + FullPath);
            end;
          end
          else
          begin
            if CompareText(Name, 'config.json') <> 0 then
            begin
              if not DeleteFile(FullPath) then
                Log('Could not remove runtime file: ' + FullPath);
            end;
          end;
        end;
      until not FindNext(FindRec);
    finally
      FindClose(FindRec);
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  DataDir: string;
begin
  if CurStep <> ssInstall then
    Exit;

  if not IsUpgradeInstall() then
    Exit;

  DataDir := ExpandConstant('{localappdata}\TranscribeMate');
  Log('Upgrade detected. Purging runtime data and preserving config.json + model caches in: ' + DataDir);
  PurgeRuntimeDataKeepConfigAndModels(DataDir);
end;
