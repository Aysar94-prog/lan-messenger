param([string]$ExistingOutput)
# Builds the Windows production native media bridge (WVC-05) against the pinned libwebrtc M155
# static library. Produces a DLL in the outputs build area. This is NOT a release step and NOT a
# packaging step: nothing is written into the repository and no produced DLL is committed.
#
# Link recipe, established by probe and required by the pinned library:
#   /std:c++20  M155 headers use std::span
#   /MT         webrtc.lib is built MT_StaticRelease; /MD fails the link with LNK2038
#   /DWEBRTC_WIN  otherwise rtc_base/platform_thread_types.h pulls POSIX sched.h
#   /DNOMINMAX    otherwise windows.h min/max macros break libwebrtc headers
#   five include roots, because third-party headers live under include/third_party/*
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$package = Join-Path $repo 'vendor/nuget/LanMessenger.TestOnly.WebRtc.Native.155.8059.2.nupkg'
# The pinned input is the same gate-verified artifact the R01 audit approved; it is referenced
# rather than duplicated, so no second 100 MB package is added to the repository. Production
# packaging (WVC-22) owns naming the shipped dependency.
$expected = '0131cad1d573250a1b9423b4e36bdfdc5946a46e43efc2c8d48e6fc03efafb6a'
if (-not (Test-Path -LiteralPath $package)) { throw 'Pinned offline native input is missing.' }
if ((Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
  throw 'Pinned offline native input changed; refusing to build.'
}
$outputRoot = 'D:\LAN-Messenger\outputs\.build\video-media'
if (-not (Test-Path -LiteralPath $outputRoot)) { New-Item -ItemType Directory -Path $outputRoot | Out-Null }
if ($ExistingOutput) {
  $out = (Resolve-Path -LiteralPath $ExistingOutput).Path
  if ([IO.Path]::GetDirectoryName($out) -ne $outputRoot -or
      [IO.Path]::GetFileName($out) -notmatch '^bridge-[a-f0-9]{32}$') {
    throw 'Only an existing direct bridge output directory can be reused.'
  }
} else {
  $out = Join-Path $outputRoot ('bridge-' + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $out | Out-Null
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [IO.Compression.ZipFile]::OpenRead($package)
  try {
    foreach ($entry in $zip.Entries) {
      $name = $entry.FullName.Replace('\', '/')
      if ($name -notlike 'sdk/webrtc/*' -or $name.EndsWith('/')) { continue }
      $relative = $name.Substring('sdk/webrtc/'.Length)
      # Headers are required to compile against this library; the notices travel with the DLL.
      if ($relative -notlike 'lib/*' -and $relative -notlike 'include/*' -and
          $relative -notin @('NOTICE', 'VERSIONS', 'DEPS')) { continue }
      $target = Join-Path $out $relative
      $directory = Split-Path $target -Parent
      if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
      [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
    }
  } finally { $zip.Dispose() }
}
# Entries are extracted with the 'sdk/webrtc/' prefix stripped, so the SDK root is $out itself.
$sdk = $out
$dev = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat'
if (-not (Test-Path -LiteralPath $dev)) { throw 'Installed VS2022 Build Tools are required; nothing will be installed.' }
$environmentLines = & cmd.exe /d /c ('call "' + $dev + '" -no_logo -arch=amd64 -host_arch=amd64 >nul && set')
if ($LASTEXITCODE -ne 0) { throw 'Failed to obtain compiler environment.' }
foreach ($line in $environmentLines) {
  if ($line -match '^([^=]+)=(.*)$' -and $Matches[1] -notin @('HOME', 'CODEX_HOME')) {
    [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
  }
}
$compilerPath = @($environmentLines | Where-Object { $_ -cmatch '^PATH=' })[0]
if (-not $compilerPath) { $compilerPath = @($environmentLines | Where-Object { $_ -match '^PATH=' })[0] }
if (-not $compilerPath) { throw 'Compiler PATH was not returned.' }
$env:Path = $compilerPath.Substring(5)

$defines = @('/DWEBRTC_WIN', '/DWIN32', '/DNOMINMAX', '/DWIN32_LEAN_AND_MEAN')
$includes = @("/I$sdk/include", "/I$sdk/include/third_party/abseil-cpp",
  "/I$sdk/include/third_party/boringssl/src/include", "/I$sdk/include/third_party/libsrtp/include",
  "/I$sdk/include/third_party/libyuv/include", "/I$sdk/include/third_party/libvpx")
$systemLibraries = @('ws2_32.lib', 'secur32.lib', 'crypt32.lib', 'iphlpapi.lib', 'winmm.lib',
  'dmoguids.lib', 'wmcodecdspuuid.lib', 'msdmo.lib', 'strmiids.lib', 'ole32.lib', 'oleaut32.lib',
  'uuid.lib', 'advapi32.lib', 'user32.lib', 'gdi32.lib', 'shell32.lib', 'shlwapi.lib', 'd3d11.lib',
  'dxgi.lib', 'mf.lib', 'mfplat.lib', 'mfuuid.lib', 'bcrypt.lib', 'avrt.lib', 'ntdll.lib',
  'userenv.lib', 'powrprof.lib', 'propsys.lib', 'psapi.lib', 'dxva2.lib', 'ksuser.lib', 'mfreadwrite.lib')

$arguments = @('/nologo', '/LD', '/std:c++20', '/EHsc', '/O2', '/MT', '/Zi', '/DNDEBUG') + $defines + $includes +
  @("/Fo$out/bridge.obj", "/Fe$out/LanMessenger.WebRtc.Native.dll",
    "/Fd$out/bridge-compile.pdb",
    (Join-Path $PSScriptRoot 'lm-webrtc-bridge.cpp'), (Join-Path $sdk 'lib/webrtc.lib'),
    '/link', '/MACHINE:X64', '/DEBUG:FULL', '/OPT:REF', '/OPT:ICF',
    "/PDB:$out/LanMessenger.WebRtc.Native.pdb", "/MAP:$out/bridge.map") + $systemLibraries
# cl.exe writes to the inherited console handle rather than through the PowerShell pipeline, so
# its output is redirected explicitly. Without this a compile failure loses its diagnostics.
$buildLog = Join-Path $out 'build.log'
$errorLog = Join-Path $out 'build.err.log'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'lm-webrtc-bridge.cpp') -Destination (Join-Path $out 'source-snapshot.cpp')
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $out 'build-script-snapshot.ps1')
$manifest = [ordered]@{
  createdUtc = [DateTime]::UtcNow.ToString('o')
  compiler = (Get-Command cl.exe).Source
  compilerVersion = (Get-Item (Get-Command cl.exe).Source).VersionInfo.FileVersion
  arguments = $arguments
  sourceSha256 = (Get-FileHash (Join-Path $out 'source-snapshot.cpp') -Algorithm SHA256).Hash
  librarySha256 = (Get-FileHash (Join-Path $sdk 'lib/webrtc.lib') -Algorithm SHA256).Hash
  sdkVersion = $env:WindowsSDKVersion
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'build-inputs.json') -Encoding UTF8
$process = Start-Process -FilePath 'cl.exe' -ArgumentList $arguments -NoNewWindow -Wait -PassThru -RedirectStandardOutput $buildLog -RedirectStandardError $errorLog
if ($process.ExitCode -ne 0) {
  Get-Content -LiteralPath $buildLog -ErrorAction SilentlyContinue | Select-Object -Last 40
  Get-Content -LiteralPath $errorLog -ErrorAction SilentlyContinue | Select-Object -Last 40
  throw "Native bridge compile/link failed (cl exit $($process.ExitCode)); log: $buildLog"
}

# Ship the notices next to the DLL. Distribution requires the complete NOTICE, the copyrights
# and patent notices, and the IJG acknowledgement; a build that dropped them would be
# non-compliant with the audit that approved this input.
foreach ($notice in @('NOTICE', 'VERSIONS')) {
  $source = Join-Path $sdk $notice
  $destination = Join-Path $out $notice
  # Extraction already placed these at the output root, because $sdk is $out. Copying a file onto
  # itself throws, so only copy when the two paths actually differ.
  if ([IO.Path]::GetFullPath($source) -ne [IO.Path]::GetFullPath($destination)) {
    Copy-Item -LiteralPath $source -Destination $destination
  }
}
$dll = Get-Item (Join-Path $out 'LanMessenger.WebRtc.Native.dll')
# The ABI contract is verified on every build, before the script reports success. A bridge that
# cannot prove it never touches a device, never reuses a handle and tears down exactly once is not
# a bridge this project should be able to produce at all.
& dotnet run --project (Join-Path $repo 'tests/video-feasibility/windows/NativeBridge/NativeBridge.csproj') -c Release -- $dll.FullName
if ($LASTEXITCODE -ne 0) { throw "Native bridge ABI check failed; preserved output: $out" }
"Native bridge built and ABI-checked: $($dll.FullName) ($([math]::Round($dll.Length / 1MB, 1)) MB); output: $out"
