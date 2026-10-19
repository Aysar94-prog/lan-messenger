param(
  [string]$CacheDir = (Join-Path $PSScriptRoot '..\..\outputs\.build\aar-cache'),
  [switch]$SkipDownload
)

# Downloads and extracts the webrtc-sdk:android AAR for build integration.
# Returns a hashtable with paths needed by build.ps1:
#   classesJar   - path to classes.jar for javac classpath
#   libDirs      - hashtable of abi->directory paths for aapt
#   extractedDir - root of extracted AAR

$ErrorActionPreference = 'Stop'
$aarVersion = '150.7871.01'
$aarUrl = "https://repo1.maven.org/maven2/io/github/webrtc-sdk/android/$aarVersion/android-$aarVersion.aar"
$aarFile = Join-Path $CacheDir "webrtc-$aarVersion.aar"
$extractedDir = Join-Path $CacheDir "webrtc-$aarVersion"

if (-not $SkipDownload -and -not (Test-Path $aarFile)) {
  Write-Output "Downloading WebRTC AAR $aarVersion from Maven Central..."
  New-Item -ItemType Directory -Force $CacheDir | Out-Null
  Invoke-WebRequest -Uri $aarUrl -OutFile $aarFile -UseBasicParsing
}

if (-not (Test-Path $extractedDir)) {
  Write-Output "Extracting WebRTC AAR..."
  # AAR is a ZIP; PowerShell doesn't like .aar extension — use .zip copy
  $zipFile = Join-Path $CacheDir "webrtc-$aarVersion.zip"
  if (-not (Test-Path $zipFile)) { Copy-Item $aarFile $zipFile }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  [System.IO.Compression.ZipFile]::ExtractToDirectory($zipFile, $extractedDir)
}

$classesJar = Join-Path $extractedDir 'classes.jar'
if (-not (Test-Path $classesJar)) {
  throw "AAR extraction failed: classes.jar not found in $extractedDir"
}

# Build lib directory mapping
$libDirs = @{}
$jniDir = Join-Path $extractedDir 'jni'
if (Test-Path $jniDir) {
  Get-ChildItem $jniDir -Directory | ForEach-Object {
    $libDirs[$_.Name] = $_.FullName
  }
}

Write-Output "WebRTC AAR ready: classes.jar + $($libDirs.Count) ABIs"
return @{
  classesJar = $classesJar
  libDirs = $libDirs
  extractedDir = $extractedDir
}