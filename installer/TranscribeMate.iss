#define MyAppName "TranscribeMate"
#define MyAppVersion "26.01.27.008"
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

[Files]
Source: "..\dist\TranscribeMate\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Parameters: "--install-gpu"; Description: "Install GPU dependencies"; Flags: postinstall skipifsilent nowait runhidden unchecked
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent unchecked
