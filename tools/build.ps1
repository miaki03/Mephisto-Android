# tools/build.ps1 - set up the local toolchain env and run a Gradle task.
# ASCII only on purpose: PowerShell 5.1 reads BOM-less files as ANSI (GBK here),
# so any Chinese comment would break parsing.
param(
    [string]$Task = "assembleDebug",
    [switch]$Install,
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"

$toolchain = if ($env:MEPHISTO_TOOLCHAIN) { $env:MEPHISTO_TOOLCHAIN } else { "E:\study\.toolchain" }
if (-not (Test-Path $toolchain)) {
    throw "Toolchain not found at $toolchain. Run tools/install-toolchain.py and tools/install-sdk-packages.py first."
}

$env:JAVA_HOME = Join-Path $toolchain "jdk"
$env:ANDROID_HOME = Join-Path $toolchain "android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $toolchain ".android"
$env:GRADLE_USER_HOME = Join-Path $toolchain "gradle-home"
# java.io.tmpdir, the Kotlin daemon and adb all need a writable TEMP.
$env:TEMP = Join-Path $toolchain "jtmp"
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null

$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\build-tools\36.0.0;$env:PATH"

$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    $arguments = @(":$Task", "--no-daemon")
    if ($Quiet) { $arguments += "--quiet" }
    & ".\gradlew.bat" @arguments
    $code = $LASTEXITCODE
    if ($code -ne 0) { exit $code }

    if ($Install) {
        $apk = "app\build\outputs\apk\debug\app-debug.apk"
        if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
        & "adb.exe" install -r $apk
        exit $LASTEXITCODE
    }
    exit 0
} finally {
    Pop-Location
}
