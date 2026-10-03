param(
  [string]$SdkRoot = 'C:\Program Files (x86)\Android\android-sdk',
  [string]$JdkRoot = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [string]$OutputDir = (Join-Path $PSScriptRoot '..\..\outputs'),
  [string]$BuildRoot = (Join-Path $PSScriptRoot '..\..\outputs\.build\android-voice'),
  [string]$VersionName = '2.2.6',
  [int]$VersionCode = 33,
  # Distinguishes a voice build from a release build of the same version, so a release artifact
  # can never be clobbered by a voice validation build.
  [string]$ApkSuffix = '-voice-dev',
  [switch]$Arm64Only
)

# Single build pipeline for the Android app (also invoked by build.ps1).
# Uses the javac/d8/aapt/apksigner pipeline, with the WebRTC AAR's classes.jar on the classpath
# and its native .so files packaged into the APK.
#
# The WebRTC dependency is mandatory: WebRtcCallMedia imports org.webrtc directly, so there is no
# "build without WebRTC" mode any more. There used to be a -SkipWebRTC switch here; it could only
# ever have produced a compile failure, so it was removed rather than left as a trap.
#
# Prerequisites:
#   .\prepare-webrtc.ps1        (once, to download and cache the AAR)
#
# Parameters:
#   -ApkSuffix   Suffix on the output file name, so a voice validation build cannot clobber a
#                release artifact of the same version
#   -Arm64Only   Package only the arm64-v8a native lib (~6 MB APK instead of ~47 MB)

# Native tools (javac/d8/aapt) write notes and warnings to stderr, which PowerShell surfaces as
# error records. 'Stop' would abort on a harmless deprecation note, so failures are detected by
# the explicit $LASTEXITCODE checks after every native call instead.
$ErrorActionPreference = 'Continue'
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)

# --- Paths ---
$toolsDir = Join-Path $SdkRoot 'build-tools\35.0.0'
$platformJar = Join-Path $SdkRoot 'platforms\android-34\android.jar'
$keyFile = Join-Path $PSScriptRoot '..\.private\development.keystore'
$manifest = Join-Path $PSScriptRoot 'AndroidManifest.xml'
$appResDir = Join-Path $PSScriptRoot 'res'

if (!(Test-Path -LiteralPath $keyFile)) {
  throw 'Signing key not found at .private\development.keystore'
}

$buildId = [Guid]::NewGuid().ToString('N')
$classesDir = Join-Path $BuildRoot "classes-$buildId"
$dexDir = Join-Path $BuildRoot "dex-$buildId"
$libDir = Join-Path $BuildRoot "lib-$buildId"
New-Item -ItemType Directory -Force $BuildRoot,$classesDir,$dexDir,$libDir | Out-Null

# --- AAR integration (A03b) ---
$aarDir = Join-Path $PSScriptRoot '..\..\outputs\.build\aar-cache\webrtc-150.7871.01'
if (-not (Test-Path $aarDir)) {
  Write-Output "WebRTC AAR not cached. Download it first:"
  Write-Output "  .\android\prepare-webrtc.ps1"
  throw "WebRTC AAR not found."
}
$aarClassesJar = Join-Path $aarDir 'classes.jar'
if (!(Test-Path $aarClassesJar)) { throw "classes.jar not found in AAR" }
$aarLibDirs = @{}

Write-Output "=== Integrating WebRTC AAR ==="
$jniDir = Join-Path $aarDir 'jni'
if (Test-Path $jniDir) {
  Get-ChildItem $jniDir -Directory | ForEach-Object {
    $abi = $_.Name
    # NOTE: no 'continue' here. In PowerShell, 'continue' inside a ForEach-Object block at
    # script scope terminates the whole script, which silently truncated this build.
    if (-not ($Arm64Only -and $abi -ne 'arm64-v8a')) {
      $destDir = Join-Path $libDir $abi
      New-Item -ItemType Directory -Force $destDir | Out-Null
      Get-ChildItem $_.FullName -Filter '*.so' | Copy-Item -Destination $destDir -Force
      $aarLibDirs[$abi] = $destDir
      $sum = (Get-ChildItem $destDir -Filter '*.so' | Measure-Object Length -Sum).Sum
      $mb = [math]::Round($sum / 1MB, 1)
      Write-Output "  $abi : $mb MB"
    }
  }
}

# --- javac ---
Write-Output "=== Compiling ==="
$sourceFiles = Get-ChildItem -LiteralPath "$PSScriptRoot\src\net\lanmsg\chat" -Filter '*.java' | ForEach-Object FullName

$classpathParts = @($platformJar)
if ($aarClassesJar) { $classpathParts += $aarClassesJar }
$classpath = [string]::Join(';', $classpathParts)

& "$JdkRoot\bin\javac.exe" -J-Xmx512m -encoding UTF-8 -source 8 -target 8 -bootclasspath "$toolsDir\core-lambda-stubs.jar;$platformJar" -classpath $classpath -d $classesDir @sourceFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Output "=== D8 (DEX) ==="
# Jar up compiled classes
& "$JdkRoot\bin\jar.exe" -J-Xmx128m cf "$BuildRoot\classes.jar" -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

$d8Inputs = @("$BuildRoot\classes.jar")
if ($aarClassesJar) {
  # Also dex the WebRTC classes
  $d8Inputs += $aarClassesJar
}

& "$JdkRoot\bin\java.exe" -Xms16m -Xmx512m -cp "$toolsDir\lib\d8.jar" com.android.tools.r8.D8 --lib $platformJar --min-api 26 --output $dexDir @d8Inputs
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Output "=== aapt (APK) ==="
# Build base APK with manifest
# aapt has no flag to override the version, so patch a copy of the manifest. Without this the
# installed versionCode/Name silently came from AndroidManifest.xml while the output file was
# named after -VersionName, so the artifact and its contents disagreed.
#
# The copy must be named exactly AndroidManifest.xml in its own directory: aapt rejects any other
# file name with "No AndroidManifest.xml file found", even when the bytes are identical.
$manifestDir = Join-Path $BuildRoot "manifest-$buildId"
New-Item -ItemType Directory -Force $manifestDir | Out-Null
$manifestForBuild = Join-Path $manifestDir 'AndroidManifest.xml'
$manifestText = [IO.File]::ReadAllText($manifest)
$manifestText = $manifestText -replace 'android:versionCode="\d+"', "android:versionCode=`"$VersionCode`""
$manifestText = $manifestText -replace 'android:versionName="[^"]*"', "android:versionName=`"$VersionName`""
[IO.File]::WriteAllText($manifestForBuild, $manifestText, (New-Object Text.UTF8Encoding($false)))
& "$toolsDir\aapt.exe" package -f -M $manifestForBuild -S $appResDir -I $platformJar -F "$BuildRoot\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "aapt package failed" }

# Add every dex d8 produced (WebRTC is large enough to need more than one)
$dexCount = 0
Push-Location $dexDir
try {
  Get-ChildItem -Filter '*.dex' | ForEach-Object {
    & "$toolsDir\aapt.exe" add "$BuildRoot\unsigned.apk" $_.Name
    if ($LASTEXITCODE -ne 0) { throw "aapt add $($_.Name) failed" }
    $dexCount++
  }
} finally { Pop-Location }
if ($dexCount -eq 0) { throw "d8 produced no dex files" }
Write-Output "  dex files: $dexCount"

# Stage native libs under lib/<abi>/ so `aapt add` places them at the right APK path.
# aapt derives the in-archive name from the file's path relative to the working directory,
# so adding from a staging root containing lib/ is what produces lib/arm64-v8a/lib*.so.
$stageDir = Join-Path $BuildRoot "stage-$buildId"
New-Item -ItemType Directory -Force $stageDir | Out-Null
$addedLibs = 0
if (Test-Path $libDir) {
  Get-ChildItem $libDir -Directory | ForEach-Object {
    $abi = $_.Name
    $dest = Join-Path $stageDir "lib\$abi"
    New-Item -ItemType Directory -Force $dest | Out-Null
    Get-ChildItem $_.FullName -Filter '*.so' | ForEach-Object {
      Copy-Item $_.FullName $dest -Force
      $addedLibs++
    }
  }
}
if ($addedLibs -gt 0) {
  Push-Location $stageDir
  try {
    Get-ChildItem -Recurse -Filter '*.so' | ForEach-Object {
      # Forward slashes are required: aapt stores the path verbatim, and a backslash produces
      # an entry named "lib\arm64-v8a\x.so" that Android's loader does not recognize.
      $rel = $_.FullName.Substring($stageDir.Length + 1) -replace '\\', '/'
      & "$toolsDir\aapt.exe" add "$BuildRoot\unsigned.apk" $rel
      if ($LASTEXITCODE -ne 0) { throw "aapt add $($rel) failed" }
    }
    Write-Output "  native libs added: $addedLibs"
  } finally { Pop-Location }
}

# Add any AAR resources (res/ from AAR if present)
if ($aarClassesJar) {
  $aarResDir = Join-Path (Split-Path -Parent $aarClassesJar) 'res'
  if (Test-Path $aarResDir) {
    Push-Location $aarResDir
    try {
      Get-ChildItem -Recurse -File | ForEach-Object {
        $relPath = $_.FullName.Substring($aarResDir.Length + 1) -replace '\\','/'
        & "$toolsDir\aapt.exe" add "$BuildRoot\unsigned.apk" $_.FullName $relPath
      }
    } finally { Pop-Location }
  }
}

# --- zipalign + sign ---
Write-Output "=== zipalign ==="
& "$toolsDir\zipalign.exe" -f 4 "$BuildRoot\unsigned.apk" "$BuildRoot\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$apkFile = Join-Path $OutputDir "LanMessenger-$VersionName$ApkSuffix.apk"
if (Test-Path -LiteralPath $apkFile) {
  throw "Output APK already exists; refusing to overwrite: $apkFile (use a different -VersionName or -ApkSuffix)"
}

Write-Output "=== Signing ==="
& "$JdkRoot\bin\java.exe" -Xmx128m -jar "$toolsDir\lib\apksigner.jar" sign --ks $keyFile --ks-pass pass:android --key-pass pass:android --out $apkFile "$BuildRoot\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

Write-Output "=== Verify ==="
& "$JdkRoot\bin\java.exe" -Xmx128m -jar "$toolsDir\lib\apksigner.jar" verify --verbose $apkFile
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

$apkSize = [math]::Round((Get-Item $apkFile).Length / 1MB, 1)
Write-Output "=== Done: $apkFile ($apkSize MB) ==="
