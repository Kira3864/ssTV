#ifndef StageDir
  #error StageDir is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif
[Setup]
AppId={{8CE21109-970A-47A8-8508-A0B864E5399B}
AppName=松鼠TV
AppVersion=0.1.1
AppPublisher=SquirrelTV
DefaultDirName={localappdata}\Programs\SquirrelTV
DefaultGroupName=松鼠TV
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=ssTV-0.1.1-Windows-x64-Setup
SetupIconFile=..\..\design\app-icon\sstv.ico
UninstallDisplayIcon={app}\SquirrelTV.exe
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
DisableProgramGroupPage=yes
[Languages]
Name: "chinesesimp"; MessagesFile: "ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"
[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; Flags: unchecked
[Files]
Source: "{#StageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{autoprograms}\松鼠TV"; Filename: "{app}\SquirrelTV.exe"
Name: "{autodesktop}\松鼠TV"; Filename: "{app}\SquirrelTV.exe"; Tasks: desktopicon
