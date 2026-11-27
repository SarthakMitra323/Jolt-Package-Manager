[Setup]
AppId={{8F6E7C31-4A1E-4D2B-9E8A-5C2D1B7A9E04}
AppName=Jolt Package Manager (fast)
AppVersion=1.0.0
AppPublisher=Sarthak Mitra
AppPublisherURL=https://github.com/SarthakMitra323/Jolt-Package-Manager
AppSupportURL=https://github.com/SarthakMitra323/Jolt-Package-Manager/issues
DefaultDirName={localappdata}\jolt
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ChangesEnvironment=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
OutputDir=..\build\native
OutputBaseFilename=JoltSetup-x64
UninstallDisplayName=Jolt Package Manager (fast)

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "..\build\native\fast.exe"; DestDir: "{app}\bin"; Flags: ignoreversion

[Code]
const
  EnvKey = 'Environment';

procedure AddToUserPath(Dir: string);
var
  OrigPath, NewPath: string;
begin
  if not RegQueryStringValue(HKEY_CURRENT_USER, EnvKey, 'Path', OrigPath) then
    OrigPath := '';

  if Pos(';' + Uppercase(Dir) + ';', ';' + Uppercase(OrigPath) + ';') > 0 then
    Exit;

  if OrigPath = '' then
    NewPath := Dir
  else if OrigPath[Length(OrigPath)] = ';' then
    NewPath := OrigPath + Dir
  else
    NewPath := OrigPath + ';' + Dir;

  RegWriteExpandStringValue(HKEY_CURRENT_USER, EnvKey, 'Path', NewPath);
end;

procedure RemoveFromUserPath(Dir: string);
var
  OrigPath, SearchStr, UpperOrig: string;
  P: Integer;
begin
  if not RegQueryStringValue(HKEY_CURRENT_USER, EnvKey, 'Path', OrigPath) then
    Exit;

  SearchStr := Uppercase(Dir);
  UpperOrig := Uppercase(OrigPath);

  P := Pos(';' + SearchStr + ';', ';' + UpperOrig + ';');
  if P = 0 then Exit;

  if P = 1 then
  begin
    Delete(OrigPath, 1, Length(Dir));
    if (Length(OrigPath) > 0) and (OrigPath[1] = ';') then
      Delete(OrigPath, 1, 1);
  end
  else
  begin
    Delete(OrigPath, P - 1, Length(Dir) + 1);
  end;

  RegWriteExpandStringValue(HKEY_CURRENT_USER, EnvKey, 'Path', OrigPath);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    AddToUserPath(ExpandConstant('{app}\bin'));
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
    RemoveFromUserPath(ExpandConstant('{app}\bin'));
end;