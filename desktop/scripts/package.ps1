param(
    [string]$QtRoot = "$PSScriptRoot/../.qt/6.8.3/msvc2022_64",
    [string]$InnoCompiler = 'D:/software/Inno Setup 6/ISCC.exe',
    [string]$CrtRoot = 'D:/software/VisualStudio/2022/BuildTools/VC/Redist/MSVC/14.44.35112/x64/Microsoft.VC143.CRT'
)
$ErrorActionPreference = 'Stop'
$desktopRoot = (Resolve-Path "$PSScriptRoot/..").Path
$QtRoot = (Resolve-Path $QtRoot).Path
$dist = "$desktopRoot/dist"
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stage = "$dist/stage/$stamp/installed"
$portable = "$dist/stage/$stamp/portable"
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item "$desktopRoot/out/native/SquirrelTV.exe" $stage
Copy-Item "$desktopRoot/.deps/mpv/libmpv-2.dll" $stage
$env:PATH = "$QtRoot/bin;$env:PATH"
& "$QtRoot/bin/windeployqt.exe" --release --qmldir "$desktopRoot/qml" --no-translations --no-opengl-sw --no-system-d3d-compiler --no-system-dxc-compiler --no-compiler-runtime "$stage/SquirrelTV.exe"
if ($LASTEXITCODE -ne 0) { throw 'Qt deployment failed' }
Copy-Item "$CrtRoot/*.dll" $stage
Copy-Item "$desktopRoot/THIRD_PARTY.md" "$stage/THIRD_PARTY.md"
Copy-Item "$desktopRoot/README.md" "$stage/README.md"
Copy-Item "$desktopRoot/packaging/licenses" "$stage/licenses" -Recurse
Copy-Item $stage $portable -Recurse
Set-Content "$portable/portable.txt" 'Store settings, history and downloads in ./data. Extract into a writable folder.' -Encoding utf8
Compress-Archive -Path "$portable/*" -DestinationPath "$dist/ssTV-0.1.1-Windows-x64-Portable.zip" -Force
& $InnoCompiler "/DStageDir=$stage" "/DOutputDir=$dist" "$desktopRoot/packaging/setup.iss"
if ($LASTEXITCODE -ne 0) { throw 'Installer build failed' }
Get-ChildItem $dist -File | Where-Object Name -ne 'SHA256SUMS.txt' | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower()
    "$hash  $($_.Name)"
} | Set-Content "$dist/SHA256SUMS.txt" -Encoding ascii
Write-Output "Install stage: $stage"
Write-Output "Portable stage: $portable"
