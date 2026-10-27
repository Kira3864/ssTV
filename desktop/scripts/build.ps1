param(
    [string]$QtRoot = "$PSScriptRoot/../.qt/6.8.3/msvc2022_64",
    [string]$MsvcRoot = 'D:/software/VisualStudio/2022/BuildTools/VC/Tools/MSVC/14.44.35207',
    [string]$SdkRoot = 'C:/Program Files (x86)/Windows Kits/10',
    [string]$SdkVersion = '10.0.26100.0',
    [switch]$SelfTests
)
$ErrorActionPreference = 'Stop'
$desktopRoot = (Resolve-Path "$PSScriptRoot/..").Path
$QtRoot = (Resolve-Path $QtRoot).Path
$env:PATH = "$MsvcRoot/bin/Hostx64/x64;$SdkRoot/bin/$SdkVersion/x64;$QtRoot/bin;$env:PATH"
$env:INCLUDE = "$MsvcRoot/include;$SdkRoot/Include/$SdkVersion/ucrt;$SdkRoot/Include/$SdkVersion/shared;$SdkRoot/Include/$SdkVersion/um;$SdkRoot/Include/$SdkVersion/winrt"
$env:LIB = "$MsvcRoot/lib/x64;$SdkRoot/Lib/$SdkVersion/ucrt/x64;$SdkRoot/Lib/$SdkVersion/um/x64"
if (!(Test-Path "$desktopRoot/.deps/mpv/mpv.lib")) {
    & "$MsvcRoot/bin/Hostx64/x64/lib.exe" "/def:$desktopRoot/resources/mpv.def" "/out:$desktopRoot/.deps/mpv/mpv.lib" /machine:x64
    if ($LASTEXITCODE -ne 0) { throw 'Cannot generate the libmpv import library' }
}
cmake -S $desktopRoot -B "$desktopRoot/out/native" -G 'NMake Makefiles' '-DCMAKE_BUILD_TYPE=Release' "-DCMAKE_PREFIX_PATH=$QtRoot" "-DSSTV_SELF_TESTS=$($SelfTests.IsPresent.ToString().ToUpper())"
if ($LASTEXITCODE -ne 0) { throw 'CMake configuration failed' }
cmake --build "$desktopRoot/out/native"
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
