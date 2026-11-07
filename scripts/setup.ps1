<#
.SYNOPSIS
Check the build environment, build a personal APK, and optionally install it.
.EXAMPLE
./scripts/setup.ps1 -CheckOnly
.EXAMPLE
./scripts/setup.ps1 -Install -Serial YOUR_DEVICE_SERIAL
#>
[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$SdkPath,
    [string]$BuildRoot,
    [switch]$CheckOnly,
    [switch]$Install,
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path $PSScriptRoot -Parent
$previousLocation = Get-Location
$previousJava = $env:JAVA_HOME
$previousAndroid = $env:ANDROID_HOME
$previousPath = $env:PATH

function Get-JavaOutput([string]$Executable, [string]$Arguments) {
    # Java writes version/settings to stderr. Capture it without PowerShell 5.1
    # treating a successful Java invocation as a terminating NativeCommandError.
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.Arguments = $Arguments
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw "Java failed (exit $($process.ExitCode))." }
        return $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
    } finally { $process.Dispose() }
}

try {
    Set-Location -LiteralPath $repoRoot
    if ($CheckOnly -and $Install) { throw 'Choose -CheckOnly or -Install, not both.' }
    if ($Install -and [string]::IsNullOrWhiteSpace($Serial)) { throw '-Install requires -Serial. Run adb devices -l first.' }
    if (!$JavaHome) { $JavaHome = $env:JAVA_HOME }
    if (!$JavaHome) {
        $javaCommand = Get-Command java -ErrorAction SilentlyContinue
        if ($javaCommand) {
            $javaSettings = Get-JavaOutput $javaCommand.Source '-XshowSettings:properties -version'
            if ($javaSettings -match '(?m)^\s*java.home\s*=\s*(.+)$') { $JavaHome = $Matches[1].Trim() }
        }
    }
    if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
        throw 'JDK 21 is required. Install a JDK, then set JAVA_HOME or pass -JavaHome.'
    }
    $javaVersion = Get-JavaOutput (Join-Path $JavaHome 'bin/java.exe') '-version'
    if ($javaVersion -notmatch 'version "21[.\"]') { throw 'Select JDK 21 with -JavaHome or JAVA_HOME.' }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"

    if (!$SdkPath) {
        $localProperties = Join-Path $repoRoot 'local.properties'
        if (Test-Path -LiteralPath $localProperties) {
            $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
            if ($sdkLine) { $SdkPath = ($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '').Trim().Replace('\:', ':').Replace('\\', '\') }
        }
    }
    if (!$SdkPath) { $SdkPath = $env:ANDROID_HOME }
    if (!$SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
    if (!$SdkPath -and $env:LOCALAPPDATA) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    if (!$SdkPath -or !(Test-Path -LiteralPath $SdkPath -PathType Container)) {
        throw 'Android SDK is required. Install it with Android Studio, then pass -SdkPath.'
    }
    $SdkPath = (Resolve-Path -LiteralPath $SdkPath).Path
    foreach ($sdkItem in @('platforms/android-36/android.jar', 'build-tools/36.0.0/aapt2.exe')) {
        if (!(Test-Path -LiteralPath (Join-Path $SdkPath $sdkItem))) {
            throw "Missing SDK component: $sdkItem. Use SDK Manager to install platforms;android-36, build-tools;36.0.0 and platform-tools."
        }
    }
    $env:ANDROID_HOME = $SdkPath
    $adb = Join-Path $SdkPath 'platform-tools/adb.exe'
    if (!(Test-Path -LiteralPath $adb)) {
        $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
        $adb = if ($adbCommand) { $adbCommand.Source } else { $null }
    }
    Write-Host 'Environment OK: JDK 21, Android SDK 36, Build Tools 36.0.0.'
    if (!$adb) { Write-Host 'ADB not found: install SDK platform-tools when you want to use -Install.' }
    if ($CheckOnly) { return }

    if ($Install) {
        if (!$adb) { throw '-Install requires SDK platform-tools (adb).' }
        $deviceState = & $adb -s $Serial get-state 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $deviceState.Trim() -ne 'device') {
            throw 'The selected device is not ready. Check adb devices -l and authorize USB debugging on the device.'
        }
    }
    # Only write machine-specific settings after a successful preflight.
    $sdkProperty = 'sdk.dir=' + $SdkPath.Replace('\', '/').Replace(':', '\:')
    $localPath = Join-Path $repoRoot 'local.properties'
    $otherLines = @(if (Test-Path -LiteralPath $localPath) { Get-Content -LiteralPath $localPath | Where-Object { $_ -notmatch '^\s*sdk\.dir\s*=' } })
    [IO.File]::WriteAllLines($localPath, [string[]]@($otherLines + $sdkProperty), [Text.UTF8Encoding]::new($false))
    $personalPath = Join-Path $repoRoot 'customization.properties'
    if (!(Test-Path -LiteralPath $personalPath)) {
        Copy-Item -LiteralPath (Join-Path $repoRoot 'customization.properties.example') -Destination $personalPath
    }
    # Keep generated classes out of long/non-ASCII source paths on Windows.
    # A stable per-checkout directory also keeps separate checkouts independent.
    if (!$BuildRoot) {
        $hasher = [Security.Cryptography.SHA256]::Create()
        try { $repoHash = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($repoRoot))).Replace('-', '').Substring(0, 12) }
        finally { $hasher.Dispose() }
        $BuildRoot = Join-Path ([IO.Path]::GetTempPath()) "yomipace-builds/$repoHash"
    }
    $BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
    Write-Host "Build output: $BuildRoot"
    & (Join-Path $repoRoot 'gradlew.bat') :core:test :app:assembleDebug :app:lintDebug "-PbuildRoot=$BuildRoot" --project-cache-dir (Join-Path $BuildRoot 'project-cache') --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Build/check failed (exit $LASTEXITCODE). Read the Gradle report before installing." }
    $apkPath = Join-Path $BuildRoot 'app/outputs/apk/debug/app-debug.apk'
    if (!(Test-Path -LiteralPath $apkPath)) { throw 'Expected APK not found in the selected build output directory.' }
    Write-Host "APK: $apkPath"
    Write-Host "SHA-256: $((Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash)"
    if ($Install) {
        & $adb -s $Serial install -r $apkPath
        if ($LASTEXITCODE -ne 0) { throw 'Installation failed. Do not uninstall an existing app to bypass a signature error; keep its data and use a different application ID.' }
        Write-Host 'Installed on the selected device. Open the app and check the welcome guide.'
    }
} finally {
    Set-Location -LiteralPath $previousLocation
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousAndroid
    $env:PATH = $previousPath
}
