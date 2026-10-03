param(
  [string]$JdkRoot = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [string]$OutputRoot = 'D:\LAN-Messenger\outputs\.build\video-contract'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
New-Item -ItemType Directory -Force $OutputRoot | Out-Null
$sources = @(
  "$repo\android\src\net\lanmsg\chat\CallProtocol.java",
  "$repo\android\src\net\lanmsg\chat\CallSignaling.java",
  "$PSScriptRoot\DraftVideoContract.java",
  "$PSScriptRoot\DraftVideoContractCheck.java",
  "$repo\android\src\net\lanmsg\chat\CallCameraPermission.java",
  "$PSScriptRoot\CallCameraPermissionCheck.java"
)
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -d $OutputRoot @sources
if ($LASTEXITCODE -ne 0) { throw 'Draft contract compile failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.DraftVideoContractCheck
if ($LASTEXITCODE -ne 0) { throw 'Draft contract tests failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.CallCameraPermissionCheck
if ($LASTEXITCODE -ne 0) { throw 'Camera permission identity tests failed' }
