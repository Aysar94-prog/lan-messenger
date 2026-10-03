param(
  [string]$JdkRoot = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [string]$SdkRoot = 'C:\Program Files (x86)\Android\android-sdk',
  [string]$OutputRoot = 'D:\LAN-Messenger\outputs\.build\video-feasibility\a06-native-boundary'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$androidJar = Join-Path $SdkRoot 'platforms\android-34\android.jar'
$webrtcJar = 'D:\LAN-Messenger\outputs\.build\aar-cache\webrtc-150.7871.01\classes.jar'
if (!(Test-Path -LiteralPath $webrtcJar)) {throw 'Pinned WebRTC AAR cache missing'}
New-Item -ItemType Directory -Force $OutputRoot | Out-Null
$sources = @('ICallMedia','CallVideoDiagnostics','CallProtocol','CallSignaling','CallCapabilities','CallVideoProtocol','CallVideoResources','WebRtcCallVideo') |
  ForEach-Object {Join-Path $repo "android\src\net\lanmsg\chat\$_.java"}
$sources += Join-Path $PSScriptRoot 'WebRtcCallVideoBoundaryCheck.java'
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -cp "$androidJar;$webrtcJar" -d $OutputRoot @sources
if ($LASTEXITCODE -ne 0) {throw 'Native boundary compilation failed'}
& "$JdkRoot\bin\java.exe" -cp "$OutputRoot;$androidJar;$webrtcJar" net.lanmsg.chat.WebRtcCallVideoBoundaryCheck
if ($LASTEXITCODE -ne 0) {throw 'Native boundary tests failed'}
# Explicit test-only JVM runner; no native/device evidence or production APK.
