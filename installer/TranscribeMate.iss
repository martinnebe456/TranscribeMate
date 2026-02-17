#define MyAppName "TranscribeMate"
#ifndef MyAppVersion
#define MyAppVersion "26.02.17.015"
#endif
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

; Close running app on upgrade
AppMutex=TranscribeMateMutex
CloseApplications=yes
CloseApplicationsFilter={#MyAppExeName}
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"; Flags: unchecked

[Files]
; Expects packaged app image in ..\dist\TranscribeMate\
Source: "..\dist\TranscribeMate\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon; WorkingDir: "{app}"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent unchecked; WorkingDir: "{app}"

[UninstallDelete]
; Remove per-user runtime data on uninstall.
Type: filesandordirs; Name: "{localappdata}\TranscribeMate"
