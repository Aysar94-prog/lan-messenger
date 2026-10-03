param([string]$ExistingOutput)
# R02 test-only native link probe, not a production build or call.
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$package = Join-Path $repo 'vendor/nuget/LanMessenger.TestOnly.WebRtc.Native.155.8059.2.nupkg'
$expected = '0131cad1d573250a1b9423b4e36bdfdc5946a46e43efc2c8d48e6fc03efafb6a'
if ((Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
  throw 'Pinned offline native input changed.'
}
$outputRoot = 'D:\LAN-Messenger\outputs\.build\video-feasibility'
if ($ExistingOutput) {
  $out = (Resolve-Path -LiteralPath $ExistingOutput).Path
  if ([IO.Path]::GetDirectoryName($out) -ne $outputRoot -or
      [IO.Path]::GetFileName($out) -notmatch '^windows-probe-[a-f0-9]{32}$') {
    throw 'Only an existing direct probe output directory can be reused.'
  }
} else {
  $out = Join-Path $outputRoot ('windows-probe-' + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $out | Out-Null
  [IO.Compression.ZipFile]::ExtractToDirectory($package,$out)
}
$sdk = Join-Path $out 'sdk/webrtc'
$dev = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat'
if (-not (Test-Path -LiteralPath $dev)) { throw 'Installed VS2022 Build Tools are required; nothing will be installed.' }
$environmentLines = & cmd.exe /d /c ('call "' + $dev + '" -no_logo -arch=amd64 -host_arch=amd64 >nul && set')
if ($LASTEXITCODE -ne 0) { throw 'Failed to obtain compiler environment.' }
foreach ($line in $environmentLines) {
  if ($line -match '^([^=]+)=(.*)$' -and $Matches[1] -notin @('HOME','CODEX_HOME')) {
    [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')
  }
}
# Some launch environments carry both Path and PATH; take VS's first PATH value.
$compilerPath = @($environmentLines | Where-Object { $_ -cmatch '^PATH=' })[0]
if (-not $compilerPath) { throw 'Compiler PATH was not returned.' }
$env:Path = $compilerPath.Substring(5)
$arguments = @('/nologo','/std:c++20','/EHsc','/O2','/MT','/DNDEBUG','/DWEBRTC_WIN','/DWIN32','/DNOMINMAX','/DLM_PROBE_RUNNER',
  "/I$sdk/include", "/I$sdk/include/third_party/abseil-cpp", "/I$sdk/include/third_party/libyuv/include",
  "/Fo$out/probe.obj", "/Fe$out/native-probe.exe", (Join-Path $PSScriptRoot 'native-probe.cpp'),
  (Join-Path $sdk 'lib/webrtc.lib'), '/link','/MACHINE:X64',
  'ws2_32.lib','secur32.lib','crypt32.lib','iphlpapi.lib','winmm.lib','dmoguids.lib','wmcodecdspuuid.lib',
  'msdmo.lib','strmiids.lib','ole32.lib','oleaut32.lib','uuid.lib','advapi32.lib','user32.lib','gdi32.lib',
  'shell32.lib','shlwapi.lib','d3d11.lib','dxgi.lib','mf.lib','mfplat.lib','mfuuid.lib')
& cl.exe @arguments
if ($LASTEXITCODE -ne 0) { throw "Native compile/link failed; preserved output: $out" }
& (Join-Path $out 'native-probe.exe')
if ($LASTEXITCODE -ne 0) { throw "Native codec probe failed; preserved output: $out" }
$dllArguments = @($arguments | Where-Object { $_ -ne '/DLM_PROBE_RUNNER' -and $_ -notlike '/Fe*' -and $_ -notlike '/Fo*' })
$dllArguments = @('/LD', "/Fo$out/bridge.obj", "/Fe$out/lm-native-probe.dll") + $dllArguments
& cl.exe @dllArguments
if ($LASTEXITCODE -ne 0) { throw "Native C ABI DLL build failed; preserved output: $out" }
& dotnet run --project (Join-Path $PSScriptRoot 'NativeProbe/NativeProbe.csproj') -c Release -- (Join-Path $out 'lm-native-probe.dll')
if ($LASTEXITCODE -ne 0) { throw "Managed ABI probe failed; preserved output: $out" }
"Probe passed; output: $out"
