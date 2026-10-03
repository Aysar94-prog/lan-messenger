param(
  [string]$SdkRoot = 'C:\Program Files (x86)\Android\android-sdk',
  [string]$JdkRoot = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [ValidateSet('arm64-v8a','armeabi-v7a','x86','x86_64')][string]$Abi = 'arm64-v8a'
)
# Explicit test-only entry point. Never calls production build/signing scripts.
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = 'D:\LAN-Messenger\outputs\.build\video-feasibility'
$run = Join-Path $root ('android-' + [Guid]::NewGuid().ToString('N'))
$tools = Join-Path $SdkRoot 'build-tools\35.0.0'
$platform = Join-Path $SdkRoot 'platforms\android-34\android.jar'
$aar = 'D:\LAN-Messenger\outputs\.build\aar-cache\webrtc-150.7871.01'
$key = Join-Path $root 'feasibility-only.keystore'
foreach ($required in @($platform, "$aar\classes.jar", "$aar\jni\$Abi\libjingle_peerconnection_so.so")) {
  if (!(Test-Path -LiteralPath $required)) { throw "Missing cached input: $required" }
}
New-Item -ItemType Directory -Force $root,"$run\classes","$run\dex","$run\stage\lib\$Abi" | Out-Null
function Check([string]$step) { if ($LASTEXITCODE -ne 0) { throw "$step failed ($LASTEXITCODE)" } }
if (!(Test-Path -LiteralPath $key)) {
  & "$JdkRoot\bin\keytool.exe" -genkeypair -keystore $key -storepass feasibility -keypass feasibility -alias test-only -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Disposable Video Feasibility,OU=Tests,O=LAN Messenger,C=ZZ'
  Check 'Throwaway key generation'
}
$sources = @(Get-ChildItem -LiteralPath "$PSScriptRoot\src" -Recurse -Filter '*.java' | ForEach-Object FullName)
$production = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\android\src\net\lanmsg\chat'))
foreach ($name in @('ICallMedia','CallVideoDiagnostics','CallProtocol','CallSignaling','CallCapabilities','CallVideoProtocol','CallVideoResources','WebRtcCallVideo','WebRtcCallMedia','CallLog')) {
  $sources += Join-Path $production "$name.java"
}
& "$JdkRoot\bin\javac.exe" -J-Xmx512m -encoding UTF-8 -source 8 -target 8 -bootclasspath "$tools\core-lambda-stubs.jar;$platform" -classpath "$platform;$aar\classes.jar" -d "$run\classes" @sources
Check 'javac'
& "$JdkRoot\bin\jar.exe" cf "$run\classes.jar" -C "$run\classes" .
Check 'jar'
& "$JdkRoot\bin\java.exe" -Xmx512m -cp "$tools\lib\d8.jar" com.android.tools.r8.D8 --lib $platform --min-api 26 --output "$run\dex" "$run\classes.jar" "$aar\classes.jar"
Check 'd8'
& "$tools\aapt.exe" package -f -M "$PSScriptRoot\AndroidManifest.xml" -I $platform -F "$run\unsigned.apk"
Check 'aapt'
Copy-Item -LiteralPath "$aar\jni\$Abi\libjingle_peerconnection_so.so" -Destination "$run\stage\lib\$Abi"
Get-ChildItem -LiteralPath "$run\dex" -Filter '*.dex' | Copy-Item -Destination "$run\stage"
Push-Location "$run\stage"
try {
  foreach ($file in (Get-ChildItem -Recurse -File)) {
    $relative = $file.FullName.Substring((Get-Location).Path.Length + 1).Replace('\','/')
    & "$tools\aapt.exe" add "$run\unsigned.apk" $relative
    Check 'aapt add'
  }
} finally { Pop-Location }
& "$tools\zipalign.exe" -f -p 4 "$run\unsigned.apk" "$run\aligned.apk"
Check 'zipalign'
$apk = Join-Path $run 'VideoFeasibility.apk'
& "$JdkRoot\bin\java.exe" -Xmx128m -jar "$tools\lib\apksigner.jar" sign --ks $key --ks-pass pass:feasibility --key-pass pass:feasibility --out $apk "$run\aligned.apk"
Check 'test APK signing'
& "$JdkRoot\bin\java.exe" -Xmx128m -jar "$tools\lib\apksigner.jar" verify --verbose $apk
Check 'signature verification'
& "$tools\aapt.exe" dump badging $apk
Check 'package identity check'
Get-FileHash -LiteralPath $apk -Algorithm SHA256
Write-Output "TEST_APK=$apk"
