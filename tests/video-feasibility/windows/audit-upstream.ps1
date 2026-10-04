param(
  [string]$Archive = 'D:\LAN-Messenger\outputs\.build\video-feasibility\upstream-m155-review\webrtc.windows_x86_64.zip',
  [string]$Package = 'D:\LAN-Messenger\source\vendor\nuget\LanMessenger.TestOnly.WebRtc.Native.155.8059.2.nupkg'
)
# R01 input audit for the ACTUAL M155 static-library candidate: the official upstream archive and
# the vendored package that would be referenced by a build. Metadata only -- never extracts, links,
# loads or executes native code, and never opens a device.
#
# This supersedes audit-input.ps1, which audited the older m150 DLL archive and therefore could not
# speak about the static-library candidate at all.
#
# The verdict below is DERIVED. Every check appends to $fail or $unresolved, and the reported gate
# is computed from those lists. Nothing here asserts PASS or HOLD as a literal: a candidate whose
# provenance or patch inclusion cannot be established lands on HOLD by construction, and a clean run
# reaches PASS only because each individual piece of evidence held.
$ErrorActionPreference = 'Stop'
# [IO.Compression.ZipFile] is a separate assembly and is not auto-loaded by Windows PowerShell 5.1.
Add-Type -AssemblyName System.IO.Compression.FileSystem

$pinned = [ordered]@{
  archiveSha256 = '3460e4fe9b7ddf01d3071f54f5eca84c528d1f961de7224f9b345b6b466d90fa'
  packageSha256 = '0131cad1d573250a1b9423b4e36bdfdc5946a46e43efc2c8d48e6fc03efafb6a'
  buildVersion = '155.8059.2.0'
  core         = 'f89edcb7be1f4be029ee7186e36b2b35ec03373e'
  libraryBytes = 369225972L
  librarySha256 = 'c5ae79fe579c9dcc10e17a49a2f3577b8f625d80317d07d34b7209b7ff42cd49'
  components   = @('webrtc','abseil-cpp','boringssl','compiler-rt','dav1d','fft','fiat','g711','g722',
                   'libaom','libc++','libjpeg_turbo','libsrtp','libvpx','libyuv','nasm','ooura','opus',
                   'perfetto','pffft','protobuf','rnnoise','sframe','spl_sqrt_floor','zlib')
}

# Advisory dispositions for this pinned input.
#
# `relation` records HOW patch inclusion against the pinned revision was established:
#   identity  - the pinned core commit IS the fix commit. Verified offline by this script.
#   ancestor  - the fix is in the pinned core's ancestor history. Carried from reviewed upstream
#               evidence; NOT machine-verifiable offline, because no webrtc git clone is available.
#               Reported as reviewed, never as machine-checked.
#   scope     - browser-layer defect absent from this standalone library. Applicability inference
#               from build scope and changed paths, not a claim that no such defect exists here.
# Anything whose relation cannot be filled from one of these is `unknown` and forces HOLD.
#
# Sources for each row are recorded in windows/video-calling/WVC-03-ADVISORY-REVIEW.md.
$advisories = @(
  [pscustomobject]@{ cve='CVE-2026-103631'; bug='chromium:567088927'
    fix='f89edcb7be1f4be029ee7186e36b2b35ec03373e'; relation='identity'
    summary='Buffer overflow in WebRTC: RTP packetizer payload capacity/reduction checks' }
  [pscustomobject]@{ cve='CVE-2026-87630'; bug='chromium:502783118'
    fix='424a6bd0b7f93659204ecccff1d61d63c25937e2'; relation='ancestor'
    summary='Moves oversized RTP payload guard earlier' }
  [pscustomobject]@{ cve='CVE-2026-87579'; bug='chromium:504690157'
    fix='caf9532b632ed80d6c0b73576d1330fb38aec248'; relation='ancestor'
    summary='Validates H264 resolution; H264 disabled by this build recipe' }
  [pscustomobject]@{ cve='CVE-2026-87430'; bug='chromium:542449805'
    fix='437408bd428c915b11f44df4f35cf1834acd61c9'; relation='ancestor'
    summary='H264 stride rounding; H264 disabled by this build recipe' }
  [pscustomobject]@{ cve='CVE-2026-79187'; bug='chromium:523296105'
    fix='5643da4a3d7eca3d0b9b523647c7e14e37f294c4'; relation='scope'
    summary='Blink media-stream disposal; browser layer, absent from standalone libwebrtc' }
  [pscustomobject]@{ cve='CVE-2026-103623'; bug='chromium:565742180'
    fix=''; relation='scope'
    summary='MediaStream use-after-free; DOM MediaStream is browser layer, not in standalone libwebrtc' }
  [pscustomobject]@{ cve='CVE-2026-102315'; bug='chromium:563351482'
    fix=''; relation='scope'
    summary='Uninitialized resource in browser Media pipeline; not part of the static library' }
)

$fail = [Collections.Generic.List[string]]::new()
$unresolved = [Collections.Generic.List[string]]::new()
$evidence = [Collections.Generic.List[string]]::new()
function Fail([string]$m) { $fail.Add($m) }
function Unresolved([string]$m) { $unresolved.Add($m) }
function Note([string]$m) { $evidence.Add($m) }

function Get-Sha256([string]$Path) {
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Get-EntrySha256($Entry) {
  $sha = [Security.Cryptography.SHA256]::Create()
  $stream = $Entry.Open()
  try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
  finally { $stream.Dispose(); $sha.Dispose() }
}
function Read-ZipText($Zip, [string]$Name, [long]$Max = 1048576) {
  $entry = $Zip.GetEntry($Name)
  if ($null -eq $entry) { Fail("missing metadata: $Name"); return $null }
  if ($entry.Length -gt $Max) { Fail("oversized metadata: $Name"); return $null }
  $reader = [IO.StreamReader]::new($entry.Open())
  try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
function Read-LibrarySignature($Zip, [string]$Name) {
  $entry = $Zip.GetEntry($Name)
  if ($null -eq $entry) { return $null }
  $stream = $entry.Open()
  try {
    $magic = [byte[]]::new(8)
    if ($stream.Read($magic, 0, 8) -ne 8) { return $null }
    return [Text.Encoding]::ASCII.GetString($magic)
  } finally { $stream.Dispose() }
}
function Test-NoticeComponents([string]$Notice) {
  if ($null -eq $Notice) { return @() }
  return @([regex]::Matches($Notice, '(?m)^# ([^\r\n]+)') | ForEach-Object { $_.Groups[1].Value })
}

# ---- provenance of the upstream archive -------------------------------------------------
if (-not (Test-Path -LiteralPath $Archive)) { Fail("upstream archive not found: $Archive") }
else {
  $archiveHash = Get-Sha256 $Archive
  if ($archiveHash -ne $pinned.archiveSha256) { Fail("upstream archive digest changed: $archiveHash") }
  else { Note("upstream archive digest matches pinned official asset: $archiveHash") }
}
if (-not (Test-Path -LiteralPath $Package)) { Fail("candidate package not found: $Package") }
else {
  $packageHash = Get-Sha256 $Package
  if ($packageHash -ne $pinned.packageSha256) { Fail("candidate package digest changed: $packageHash") }
  else { Note("candidate package digest matches pinned value: $packageHash") }
}

$core = $null
$buildVersionParsed = $null
$packageLibraryMatchesArchive = $false
$archiveComponents = @()
# NB: PowerShell variable names are case-insensitive, so the handle must not be called $archive --
# that would overwrite the $Archive path parameter.
$archiveZip = $null
if (Test-Path -LiteralPath $Archive) {
  $archiveZip = [IO.Compression.ZipFile]::OpenRead($Archive)
  try {
    $paths = @($archiveZip.Entries | ForEach-Object { $_.FullName.Replace('\','/') })
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($path in $paths) {
      if ($path -match '(^|/)\.\.(/|$)|^/|:|\x00' -or -not $path.StartsWith('webrtc/')) {
        Fail("unsafe upstream archive path: $path")
      }
      if (-not $seen.Add($path)) { Fail("duplicate or case-aliased archive path: $path") }
    }
    Note("archive entries checked for safe, non-aliased paths: $($paths.Count)")

    $versions = Read-ZipText $archiveZip 'webrtc/VERSIONS'
    if ($null -ne $versions) {
      $m = [regex]::Match($versions, '(?m)^WEBRTC_BUILD_VERSION=(\S+)\r?$')
      if (-not $m.Success) { Fail('VERSIONS does not state a build version') }
      elseif ($m.Groups[1].Value -ne $pinned.buildVersion) { Fail("build version changed: $($m.Groups[1].Value)") }
      else { $buildVersionParsed = $m.Groups[1].Value; Note("build version pinned: $buildVersionParsed") }

      $c = [regex]::Match($versions, '(?m)^WEBRTC_SRC_COMMIT=([0-9a-f]{40})\r?$')
      if (-not $c.Success) { Unresolved('VERSIONS does not state a WebRTC source commit') }
      else {
        $core = $c.Groups[1].Value
        if ($core -ne $pinned.core) { Fail("pinned core commit changed: $core") }
        else { Note("core commit read from VERSIONS: $core") }
      }
    }

    $archiveNotice = Read-ZipText $archiveZip 'webrtc/NOTICE'
    $archiveComponents = @(Test-NoticeComponents $archiveNotice)
    if (@(Compare-Object $pinned.components $archiveComponents).Count) {
      Fail('upstream NOTICE component inventory changed; manual license review required')
    } else {
      Note("upstream NOTICE lists $($archiveComponents.Count) expected components")
    }

    $library = $archiveZip.GetEntry('webrtc/lib/webrtc.lib')
    if ($null -eq $library) { Fail('upstream static library missing') }
    else {
      if ($library.Length -ne $pinned.libraryBytes) { Fail("static library size changed: $($library.Length)") }
      else {
        $libHash = Get-EntrySha256 $library
        if ($libHash -ne $pinned.librarySha256) { Fail("static library digest changed: $libHash") }
        else { Note("static library pinned: $($library.Length) bytes, sha256 $libHash") }
      }
      if ((Read-LibrarySignature $archiveZip 'webrtc/lib/webrtc.lib') -ne "!<arch>`n") {
        Fail('static library is not a COFF archive')
      } else { Note('static library carries a valid COFF archive signature') }
    }
    if (@($paths | Where-Object { $_ -match '\.(dll|exe)$' }).Count) { Fail('unexpected executable input in archive') }
    else { Note('archive contains no executable DLL/EXE payload') }
  } finally { $archiveZip.Dispose() }
}

# ---- the candidate that a build would actually reference --------------------------------
$packageComponents = @()
if (Test-Path -LiteralPath $Package) {
  $packageZip = [IO.Compression.ZipFile]::OpenRead($Package)
  try {
    $pkgPaths = @($packageZip.Entries | ForEach-Object { $_.FullName.Replace('\','/') })
    # A nupkg legitimately carries package metadata at its root, so the sdk/ prefix rule that suits
    # the upstream archive does not apply here. Traversal protection is unchanged; the root is
    # constrained to exactly one manifest plus the declared input manifest, and the manifest name is
    # reported rather than guessed (NuGet names it by package id, not by the versioned filename).
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($path in $pkgPaths) {
      if ($path -match '(^|/)\.\.(/|$)|^/|:|\x00') {
        Fail("unsafe package path: $path")
      } elseif ($path -notmatch '/' -and $path -ne 'input-manifest.json' -and $path -notmatch '\.nuspec$') {
        Fail("unexpected file at package root: $path")
      } elseif ($path -match '/' -and -not $path.StartsWith('sdk/')) {
        Fail("unexpected package path outside sdk/: $path")
      }
      if (-not $seen.Add($path)) { Fail("duplicate or case-aliased package path: $path") }
    }
    $rootManifests = @($pkgPaths | Where-Object { $_ -notmatch '/' })
    if (@($rootManifests | Where-Object { $_ -match '\.nuspec$' }).Count -ne 1) {
      Fail("expected exactly one package manifest at the root, found $(@($rootManifests | Where-Object { $_ -match '\.nuspec$' }).Count)")
    } else {
      Note("package root metadata: $($rootManifests -join ', ')")
    }
    Note("package entries checked for safe, non-aliased paths: $($pkgPaths.Count)")
    if (@($pkgPaths | Where-Object { $_ -match '\.(dll|exe)$' }).Count) { Fail('unexpected executable input in package') }

    $pkgNotice = Read-ZipText $packageZip 'sdk/webrtc/NOTICE'
    $packageComponents = @(Test-NoticeComponents $pkgNotice)
    if (@(Compare-Object $pinned.components $packageComponents).Count) {
      Fail('package NOTICE component inventory differs from the approved inventory')
    } else {
      Note("package NOTICE carries all $($packageComponents.Count) approved components")
    }
    $pkgVersions = Read-ZipText $packageZip 'sdk/webrtc/VERSIONS'
    if ($null -ne $pkgVersions -and $null -ne $core) {
      $pc = [regex]::Match($pkgVersions, '(?m)^WEBRTC_SRC_COMMIT=([0-9a-f]{40})\r?$')
      if (-not $pc.Success) { Unresolved('package VERSIONS does not state a WebRTC source commit') }
      elseif ($pc.Groups[1].Value -ne $core) { Fail('package core commit differs from the audited archive core commit') }
      else { Note('package carries the same core commit as the audited archive') }
    }

    # Store-and-forward integrity: the vendored library must be byte-identical to the audited one,
    # otherwise the shipped artefact is not the artefact that was reviewed.
    $pkgLib = $packageZip.GetEntry('sdk/webrtc/lib/webrtc.lib')
    if ($null -eq $pkgLib) { Fail('candidate package is missing the static library') }
    else {
      if ($pkgLib.Length -ne $pinned.libraryBytes) { Fail("package library size changed: $($pkgLib.Length)") }
      $pkgLibHash = Get-EntrySha256 $pkgLib
      if ($pkgLibHash -ne $pinned.librarySha256) { Fail("package library digest changed: $pkgLibHash") }
      else {
        $packageLibraryMatchesArchive = $true
        Note('package library is byte-identical to the audited upstream library')
      }
      if ((Read-LibrarySignature $packageZip 'sdk/webrtc/lib/webrtc.lib') -ne "!<arch>`n") {
        Fail('package library is not a COFF archive')
      }
    }
    if (@(Compare-Object $archiveComponents $packageComponents).Count) {
      Unresolved('could not cross-check package notice inventory against the archive')
    }
  } finally { $packageZip.Dispose() }
}

# ---- advisory patch inclusion against the exact pinned revision -------------------------
$verified = 0; $reviewed = 0; $inferred = 0
foreach ($row in $advisories) {
  switch ($row.relation) {
    'identity' {
      if ($null -eq $core) { Unresolved("$($row.cve): pinned core unknown, cannot verify identity") }
      elseif ($row.fix -eq $core) { $verified++; Note("$($row.cve): fix commit IS the pinned core $core") }
      else { Fail("$($row.cve): expected the fix to be the pinned core, but pin is $core") }
    }
    'ancestor' {
      if ([string]::IsNullOrWhiteSpace($row.fix)) { Unresolved("$($row.cve): ancestor relation with no fix commit") }
      else { $reviewed++; Note("$($row.cve): fix $($row.fix) reviewed as in the pinned ancestor history (not machine-verified offline)") }
    }
    'scope' {
      $inferred++; Note("$($row.cve): browser-layer scope, applicability inferred from build scope")
    }
    default { Unresolved("$($row.cve): no established relation to the pinned revision") }
  }
}

$gate = if ($fail.Count -or $unresolved.Count) { 'HOLD' } else { 'PASS' }
$reasons = @()
$reasons += @($fail | ForEach-Object { "FAILED: $_" })
$reasons += @($unresolved | ForEach-Object { "UNRESOLVED: $_" })

[pscustomobject]@{
  task = 'R01 Windows M155 static-library candidate audit'
  archiveSha256 = $pinned.archiveSha256
  packageSha256 = $pinned.packageSha256
  coreCommitFromVersions = $core
  buildVersionFromVersions = $buildVersionParsed
  staticLibrarySha256 = $pinned.librarySha256
  noticeComponents = $packageComponents.Count
  packageLibraryMatchesArchive = $packageLibraryMatchesArchive
  advisoryFixesMachineVerified = $verified
  advisoryFixesReviewedNotMachineVerified = $reviewed
  advisoryApplicabilityInferred = $inferred
  nativeCodeExecuted = $false
  gate = $gate
  reasons = $reasons
  evidence = $evidence
} | ConvertTo-Json -Depth 5

if ($gate -eq 'HOLD') { exit 2 } else { exit 0 }