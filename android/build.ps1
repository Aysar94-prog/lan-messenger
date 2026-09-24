param(
  [string]$SdkRoot = 'C:\Program Files (x86)\Android\android-sdk',
  [string]$JdkRoot = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [string]$BuildRoot = (Join-Path $PSScriptRoot '..\..\outputs\.build\android'),
  [switch]$GenerateDevelopmentKey
)
$ErrorActionPreference = 'Stop'
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
$keyFile = Join-Path $PSScriptRoot '..\.private\development.keystore'
if (!(Test-Path -LiteralPath $keyFile) -and !$GenerateDevelopmentKey) {
  throw 'Existing signing key not found. Restore it to BuildRoot to preserve upgrade compatibility. Use -GenerateDevelopmentKey only for a separate new development installation.'
}
$toolsDir = Join-Path $SdkRoot 'build-tools\35.0.0'
$platformJar = Join-Path $SdkRoot 'platforms\android-34\android.jar'
$buildId = [Guid]::NewGuid().ToString('N')
$classesDir = Join-Path $BuildRoot "classes-$buildId"
$dexDir = Join-Path $BuildRoot "dex-$buildId"
New-Item -ItemType Directory -Force $BuildRoot,$classesDir,$dexDir | Out-Null
function Check-Result { if ($LASTEXITCODE -ne 0) { throw "Build command failed with exit code $LASTEXITCODE" } }
$sourceFiles = Get-ChildItem -LiteralPath "$PSScriptRoot\src\net\lanmsg\chat" -Filter '*.java' | ForEach-Object FullName
& "$JdkRoot\bin\javac.exe" -J-Xmx256m -encoding UTF-8 -source 8 -target 8 -bootclasspath "$toolsDir\core-lambda-stubs.jar;$platformJar" -d $classesDir $sourceFiles
Check-Result
& "$JdkRoot\bin\jar.exe" -J-Xmx128m cf "$BuildRoot\classes.jar" -C $classesDir .
Check-Result
& "$JdkRoot\bin\java.exe" -Xms16m -Xmx384m -cp "$toolsDir\lib\d8.jar" com.android.tools.r8.D8 --lib $platformJar --min-api 26 --output $dexDir "$BuildRoot\classes.jar"
Check-Result
& "$toolsDir\aapt.exe" package -f -M "$PSScriptRoot\AndroidManifest.xml" -I $platformJar -F "$BuildRoot\unsigned.apk"
Check-Result
Push-Location $dexDir
try { & "$toolsDir\aapt.exe" add "$BuildRoot\unsigned.apk" classes.dex; Check-Result } finally { Pop-Location }
& "$toolsDir\zipalign.exe" -f 4 "$BuildRoot\unsigned.apk" "$BuildRoot\aligned.apk"
Check-Result
$keyFile = Join-Path $PSScriptRoot '..\.private\development.keystore'
if (!(Test-Path -LiteralPath $keyFile)) {
  New-Item -ItemType Directory -Force (Split-Path -Parent $keyFile) | Out-Null
  & "$JdkRoot\bin\keytool.exe" -genkeypair -keystore $keyFile -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=LAN Messenger Development,O=LAN Messenger,C=US'
  Check-Result
}
$apkFile = Join-Path $PSScriptRoot '..\..\outputs\LanMessenger-0.8.7.apk'
& "$JdkRoot\bin\java.exe" -Xmx128m -jar "$toolsDir\lib\apksigner.jar" sign --ks $keyFile --ks-pass pass:android --key-pass pass:android --out $apkFile "$BuildRoot\aligned.apk"
Check-Result
& "$JdkRoot\bin\java.exe" -Xmx128m -jar "$toolsDir\lib\apksigner.jar" verify --verbose $apkFile
Check-Result
Write-Output "APK ready: $apkFile"
