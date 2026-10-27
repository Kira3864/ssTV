param([string]$SevenZip = 'D:/Programs/7-Zip/7z.exe')
$ErrorActionPreference = 'Stop'
$desktopRoot = (Resolve-Path "$PSScriptRoot/..").Path
python -m pip install aqtinstall==3.3.0 --index-url https://pypi.org/simple
if ($LASTEXITCODE -ne 0) { throw 'aqtinstall setup failed' }
python -m aqt install-qt windows desktop 6.8.3 win64_msvc2022_64 -O "$desktopRoot/.qt" --archives qtbase qtdeclarative qtsvg
if ($LASTEXITCODE -ne 0) { throw 'Qt setup failed' }
New-Item -ItemType Directory -Force "$desktopRoot/.deps" | Out-Null
$archive = "$desktopRoot/.deps/mpv.7z"
Invoke-WebRequest 'https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261021/mpv-dev-x86_64-20261021-git-e471496c56.7z' -OutFile $archive
& $SevenZip x $archive "-o$desktopRoot/.deps/mpv" -y
if ($LASTEXITCODE -ne 0) { throw 'libmpv extraction failed' }
