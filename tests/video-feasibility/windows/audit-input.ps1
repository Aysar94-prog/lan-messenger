param(
  [string]$Archive = 'D:\LAN-Messenger\outputs\.build\video-feasibility\libwebrtc-review\libwebrtc-win-x64-release.zip',
  [string]$PublishedChecksum = 'D:\LAN-Messenger\outputs\.build\video-feasibility\libwebrtc-review\libwebrtc-win-x64-release.zip.shasum'
)
# R01 read-only input gate. Does not load DLLs, restore packages or run media.
$ErrorActionPreference = 'Stop'
# [IO.Compression.ZipFile] lives in a separate assembly and is not auto-loaded by Windows PowerShell
# 5.1, so the gate threw before reaching any check. Loading it explicitly is harmless on PowerShell 7+.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$expectedHash = '4cd8fce2939b67b034200b124e4559cf343387a047118755925a0551328df84d'
$actualHash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
$publishedHash = ((Get-Content -LiteralPath $PublishedChecksum -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
if ($actualHash -ne $expectedHash -or $publishedHash -ne $expectedHash) {
  throw 'Candidate archive checksum does not match pinned and published SHA256.'
}
$zip = [IO.Compression.ZipFile]::OpenRead($Archive)
try {
  $entries = @($zip.Entries | ForEach-Object { $_.FullName.Replace('\','/') })
  if (@($entries | Where-Object { $_ -match '(^|/)\.\.(/|$)|^[A-Za-z]:|^/' }).Count) {
    throw 'Unsafe archive entry path.'
  }
  $dlls = @($zip.Entries | Where-Object { $_.FullName.Replace('\','/') -match '/lib/libwebrtc\.dll$' })
  if ($dlls.Count -ne 1) { throw 'Expected exactly one candidate libwebrtc.dll.' }
  $stream = $dlls[0].Open()
  $buffer = [IO.MemoryStream]::new()
  try { $stream.CopyTo($buffer); $bytes = $buffer.ToArray() }
  finally { $stream.Dispose(); $buffer.Dispose() }
  if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x4d -or $bytes[1] -ne 0x5a) { throw 'Invalid PE input.' }
  $peOffset = [BitConverter]::ToInt32($bytes,60)
  if ($peOffset -lt 64 -or $peOffset -gt $bytes.Length - 26) { throw 'Invalid PE header offset.' }
  if ([BitConverter]::ToUInt32($bytes,$peOffset) -ne 0x4550) { throw 'Invalid PE signature.' }
  $machine = [BitConverter]::ToUInt16($bytes,$peOffset + 4)
  if ($machine -ne 0x8664) { throw 'Candidate is not x64; architecture changed.' }
  # [Security.Cryptography.SHA256]::HashData and [Convert]::ToHexString are .NET 5+ APIs and do not
  # exist in Windows PowerShell 5.1, so this gate could not complete there either. The streaming API
  # behaves identically on 5.1 and 7+; keeping lower-case hex means recorded hashes stay comparable
  # across runs and PowerShell versions.
  $sha = [Security.Cryptography.SHA256]::Create()
  try { $dllHash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
  finally { $sha.Dispose() }
  $noticeFiles = @($entries | Where-Object { $_ -match '(^|/)(NOTICE|LICENSE_THIRD_PARTY|THIRD_PARTY_NOTICES)(\.[^/]*)?$' })
  [pscustomobject]@{
    task = 'R01 Windows dependency audit'
    archiveSha256 = $actualHash
    publishedChecksumMatches = $true
    dllSha256 = $dllHash
    architecture = 'x64'
    entryCount = $entries.Count
    bundledThirdPartyNoticeFiles = $noticeFiles
    sourceRevisionVerified = $false
    securityDispositionComplete = $false
    selected = $false
    gate = 'HOLD'
    reasons = @(
      'Release recipe uses mutable m150_release; exact binary core revision is not established.',
      'Archive has wrapper MIT license only; delivered static-component notices are not established.',
      'Current WebRTC advisories still need revision-specific patch/applicability disposition.'
    )
  } | ConvertTo-Json -Depth 4
  exit 2 # Expected rejection. A matching checksum is necessary, not gate acceptance.
}
finally { $zip.Dispose() }
