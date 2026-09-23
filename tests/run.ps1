param(
  [string]$JdkRoot = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [string]$TestRoot = (Join-Path $PSScriptRoot '..\..\..\work\release-tests')
)
$ErrorActionPreference = 'Stop'
$TestRoot = [IO.Path]::GetFullPath($TestRoot)
New-Item -ItemType Directory -Force $TestRoot | Out-Null
function Check-Result { if ($LASTEXITCODE -ne 0) { throw "Test/build failed: $LASTEXITCODE" } }
Push-Location (Join-Path $PSScriptRoot '..')
try {
  dotnet build tests/CsharpHarness/CsharpHarness.csproj -c Release --configfile NuGet.Config -o "$TestRoot\csharp"
  Check-Result
  & "$JdkRoot\bin\javac.exe" -J-Xmx256m -encoding UTF-8 -d "$TestRoot\java" android/src/net/lanmsg/chat/PeerEngine.java android/src/net/lanmsg/chat/SecureIdentity.java tests/PeerHarness.java tests/TestProtector.java
  Check-Result
  python tests/integration.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/features.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/transfers.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  dotnet build tests/WindowsUi/WindowsUi.csproj -c Release --configfile NuGet.Config -o "$TestRoot\ui"
  Check-Result
  dotnet "$TestRoot\ui\WindowsUi.dll" "$TestRoot\ui-results"
  Check-Result
} finally { Pop-Location }
