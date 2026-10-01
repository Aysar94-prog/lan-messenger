param(
  [string]$SdkRoot = 'C:\Program Files (x86)\Android\android-sdk',
  [string]$JdkRoot = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [string]$BuildRoot = (Join-Path $PSScriptRoot '..\..\outputs\.build\android'),
  [string]$VersionName = '2.2.6',
  [int]$VersionCode = 33,
  [switch]$GenerateDevelopmentKey,
  [switch]$Arm64Only
)

# Entry point for a standard LAN Messenger build.
#
# The app links the WebRTC native library (io.github.webrtc-sdk:android), so the AAR classes must
# be on the javac classpath and the .so files must be packaged. All of that lives in
# build-voice.ps1, which is the single build pipeline; this script is a thin wrapper that keeps
# the documented build.ps1 entry point and its signing-key contract intact.
#
# Use -Arm64Only for a ~6 MB APK instead of ~47 MB when the APK only has to run on arm64 devices.

$ErrorActionPreference = 'Stop'

$keyFile = Join-Path $PSScriptRoot '..\.private\development.keystore'
if (!(Test-Path -LiteralPath $keyFile)) {
  if (!$GenerateDevelopmentKey) {
    throw 'Existing signing key not found. Restore it to .private\development.keystore to preserve upgrade compatibility. Use -GenerateDevelopmentKey only for a separate new development installation.'
  }
  New-Item -ItemType Directory -Force (Split-Path -Parent $keyFile) | Out-Null
  & "$JdkRoot\bin\keytool.exe" -genkeypair -keystore $keyFile -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=LAN Messenger Development,O=LAN Messenger,C=US'
  if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }
}

$voiceBuild = @{
  SdkRoot     = $SdkRoot
  JdkRoot     = $JdkRoot
  BuildRoot   = $BuildRoot
  VersionName = $VersionName
  VersionCode = $VersionCode
  # No suffix: this is the standard artifact name, and build-voice.ps1 refuses to overwrite
  # an existing file.
  ApkSuffix   = ''
}
if ($Arm64Only) { $voiceBuild.Arm64Only = $true }

& (Join-Path $PSScriptRoot 'build-voice.ps1') @voiceBuild
if ($LASTEXITCODE -ne 0) { throw "build-voice.ps1 failed with exit code $LASTEXITCODE" }
