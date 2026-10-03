param(
  [string]$Archive = 'D:\LAN-Messenger\outputs\.build\video-feasibility\upstream-m155-review\webrtc.windows_x86_64.zip'
)
# R01 metadata only: never extract, link, load native code or open devices.
$ErrorActionPreference = 'Stop'
$expectedHash = '3460e4fe9b7ddf01d3071f54f5eca84c528d1f961de7224f9b345b6b466d90fa'
$expectedCore = 'f89edcb7be1f4be029ee7186e36b2b35ec03373e'
$expectedComponents = @('webrtc','abseil-cpp','boringssl','compiler-rt','dav1d','fft','fiat','g711','g722','libaom','libc++','libjpeg_turbo','libsrtp','libvpx','libyuv','nasm','ooura','opus','perfetto','pffft','protobuf','rnnoise','sframe','spl_sqrt_floor','zlib')
$hash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hash -ne $expectedHash) { throw 'Upstream archive does not match the pinned official asset digest.' }
$zip = [IO.Compression.ZipFile]::OpenRead($Archive)
try {
  $paths = @($zip.Entries | ForEach-Object { $_.FullName.Replace('\','/') })
  $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
  foreach ($path in $paths) {
    if ($path -match '(^|/)\.\.(/|$)|^/|:|\x00' -or -not $path.StartsWith('webrtc/')) {
      throw "Unsafe upstream archive path: $path"
    }
    if (-not $seen.Add($path)) { throw "Duplicate or case-aliased archive path: $path" }
  }
  function Read-Metadata([string]$Name) {
    $entry = $zip.GetEntry($Name)
    if ($null -eq $entry -or $entry.Length -gt 1048576) { throw "Missing/oversized metadata: $Name" }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
  }
  $versions = Read-Metadata 'webrtc/VERSIONS'
  if ($versions -notmatch '(?m)^WEBRTC_BUILD_VERSION=155\.8059\.2\.0\r?$' -or
      $versions -notmatch "(?m)^WEBRTC_COMMIT=$expectedCore\r?`$" -or
      $versions -notmatch "(?m)^WEBRTC_SRC_COMMIT=$expectedCore\r?`$") {
    throw 'Upstream version/core provenance changed.'
  }
  $notice = Read-Metadata 'webrtc/NOTICE'
  $components = @([regex]::Matches($notice, '(?m)^# ([^\r\n]+)') | ForEach-Object { $_.Groups[1].Value })
  if (@(Compare-Object $expectedComponents $components).Count) { throw 'Component notice inventory changed; manual review required.' }
  $library = $zip.GetEntry('webrtc/lib/webrtc.lib')
  if ($null -eq $library -or $library.Length -ne 369225972) { throw 'Unexpected static library input.' }
  if (@($paths | Where-Object { $_ -match '\.(dll|exe)$' }).Count) { throw 'Unexpected executable input.' }
  $libStream = $library.Open()
  try {
    $magic = [byte[]]::new(8)
    if ($libStream.Read($magic,0,8) -ne 8 -or [Text.Encoding]::ASCII.GetString($magic) -ne "!<arch>`n") {
      throw 'Invalid COFF archive signature.'
    }
  } finally { $libStream.Dispose() }
  [pscustomobject]@{
    task = 'R01 Windows upstream input audit'
    archiveSha256 = $hash
    core = $expectedCore
    buildVersion = '155.8059.2.0'
    entryCount = $paths.Count
    staticLibraryBytes = $library.Length
    noticeComponents = $components
    nativeCodeExecuted = $false
    prototypeArchitecture = 'x64 only; not a production architecture decision'
    gate = 'HOLD'
    reasons = @('Offline input packaging/header-license scope is not complete.','Prototype ABI/toolchain and R03/R04 device evidence remain pending.')
  } | ConvertTo-Json -Depth 4
  exit 2
} finally { $zip.Dispose() }
