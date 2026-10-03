param(
  [string]$JdkRoot = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [string]$SdkRoot = 'C:\Program Files (x86)\Android\android-sdk',
  [string]$OutputRoot = ('D:\LAN-Messenger\outputs\.build\video-feasibility\a07-network-' + [guid]::NewGuid().ToString('N'))
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$platform = Join-Path $SdkRoot 'platforms\android-34\android.jar'
$tools = Join-Path $SdkRoot 'build-tools\35.0.0'
$aar = 'D:\LAN-Messenger\outputs\.build\aar-cache\webrtc-150.7871.01\classes.jar'
New-Item -ItemType Directory -Force "$OutputRoot\classes","$OutputRoot\checks" | Out-Null
$sources = @(Get-ChildItem -LiteralPath "$repo\android\src\net\lanmsg\chat" -Filter '*.java' | ForEach-Object FullName)
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$tools\core-lambda-stubs.jar;$platform" -cp "$platform;$aar" -d "$OutputRoot\classes" @sources
if ($LASTEXITCODE -ne 0) {throw 'Production source compile failed'}
$tests = @("$repo\tests\TestProtector.java","$PSScriptRoot\CallCapabilitiesNetworkCheck.java","$PSScriptRoot\CallChannelNetworkCheck.java")
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -cp "$OutputRoot\classes;$platform" -d "$OutputRoot\checks" @tests
if ($LASTEXITCODE -ne 0) {throw 'Network test compile failed'}
$classpath = "$OutputRoot\checks;$OutputRoot\classes"
& "$JdkRoot\bin\java.exe" -cp $classpath net.lanmsg.chat.CallCapabilitiesNetworkCheck "$OutputRoot\capabilities"
if ($LASTEXITCODE -ne 0) {throw 'Verified capability check failed'}
& "$JdkRoot\bin\java.exe" -cp $classpath net.lanmsg.chat.CallChannelNetworkCheck "$OutputRoot\channels"
if ($LASTEXITCODE -ne 0) {throw 'Authenticated call channel check failed'}
Write-Output "NETWORK_BOUNDARY_ROOT=$OutputRoot"
# Explicit actual TLS / fake-media checks. No production installation or listening.
