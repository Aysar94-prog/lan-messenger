param(
  [string]$Archive = 'D:\LAN-Messenger\outputs\.build\video-feasibility\upstream-m155-review\webrtc.windows_x86_64.zip'
)
# R01 minimal, offline test SDK packaging. No native code execution.
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
& pwsh -NoProfile -File (Join-Path $PSScriptRoot 'audit-upstream.ps1') -Archive $Archive
if ($LASTEXITCODE -ne 2) { throw 'Metadata audit did not reach the expected reviewed HOLD.' }
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$target = Join-Path $sourceRoot 'vendor/nuget/LanMessenger.TestOnly.WebRtc.Native.155.8059.2.nupkg'
if (Test-Path -LiteralPath $target) { throw 'Refusing to overwrite an existing native input package.' }
$allowedOwnTrees = @('api','audio','call','common_audio','common_video','logging','media','modules','net','p2p','pc','rtc_base','system_wrappers','video')
$allowedDependencyTrees = @('abseil-cpp','boringssl','libyuv','libsrtp','libvpx')
$source = [IO.Compression.ZipFile]::OpenRead($Archive)
$destination = $null
try {
  $destination = [IO.Compression.ZipFile]::Open($target,[IO.Compression.ZipArchiveMode]::Create)
  $files = [Collections.Generic.List[object]]::new()
  foreach ($entry in $source.Entries) {
    $path = $entry.FullName.Replace('\','/')
    $parts = $path.Split('/')
    $include = $path -in @('webrtc/NOTICE','webrtc/VERSIONS','webrtc/DEPS','webrtc/lib/webrtc.lib')
    if ($parts.Length -ge 4 -and $parts[1] -eq 'include') {
      $include = ($parts[2] -in $allowedOwnTrees) -or
        ($parts[2] -eq 'third_party' -and $parts[3] -in $allowedDependencyTrees)
    }
    if (-not $include -or $path.EndsWith('/')) { continue }
    $copy = $destination.CreateEntry('sdk/' + $path,[IO.Compression.CompressionLevel]::Optimal)
    $inputStream = $entry.Open()
    $outputStream = $copy.Open()
    try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
    $files.Add([pscustomobject]@{path=$path;bytes=$entry.Length})
  }
  $manifest = [ordered]@{
    purpose='test-only Windows x64 feasibility SDK; not production selection'
    upstream='https://github.com/shiguredo-webrtc-build/webrtc-build/releases/tag/m155.8059.2.0'
    upstreamSha256='3460e4fe9b7ddf01d3071f54f5eca84c528d1f961de7224f9b345b6b466d90fa'
    core='f89edcb7be1f4be029ee7186e36b2b35ec03373e'
    ownHeaderTrees=$allowedOwnTrees
    dependencyHeaderTrees=$allowedDependencyTrees
    excluded='All other upstream header trees, including Blink, FFmpeg, LLVM, ML libraries and browser headers'
    notice='sdk/webrtc/NOTICE; preserve unchanged with binary distribution'
    files=$files
  }
  $metadata = $destination.CreateEntry('input-manifest.json')
  $writer = [IO.StreamWriter]::new($metadata.Open(),[Text.UTF8Encoding]::new($false))
  try { $writer.Write(($manifest | ConvertTo-Json -Depth 5)) } finally { $writer.Dispose() }
  $nuspec = $destination.CreateEntry('LanMessenger.TestOnly.WebRtc.Native.nuspec')
  $writer = [IO.StreamWriter]::new($nuspec.Open(),[Text.UTF8Encoding]::new($false))
  try {
    $writer.Write('<?xml version="1.0"?><package><metadata><id>LanMessenger.TestOnly.WebRtc.Native</id><version>155.8059.2</version><authors>Google WebRTC contributors; shiguredo build contributors</authors><description>Test-only Windows x64 static WebRTC input, filtered headers. Not referenced by production projects. Complete bundled license terms in NOTICE.</description><license type="file">sdk/webrtc/NOTICE</license><requireLicenseAcceptance>true</requireLicenseAcceptance></metadata></package>')
  } finally { $writer.Dispose() }
} finally {
  if ($null -ne $destination) { $destination.Dispose() }
  $source.Dispose()
}
[pscustomobject]@{package=$target;bytes=(Get-Item -LiteralPath $target).Length;sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant();fileCount=$files.Count} | ConvertTo-Json
