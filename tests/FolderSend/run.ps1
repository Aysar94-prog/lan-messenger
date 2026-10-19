param(
    [string]$OutputRoot = 'D:\Projects AI\LAN-Messenger\outputs\.build\send-folder',
    [string]$LogRoot = ('D:\BuildCache\logs\LAN-Messenger\send-folder-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
)
$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
New-Item -ItemType Directory -Force $OutputRoot,$LogRoot | Out-Null
Push-Location $sourceRoot
try {
    dotnet build tests/FolderSend/FolderSend.csproj -c Release --configfile NuGet.Config -o "$OutputRoot\tests" *> "$LogRoot\build.log"
    $buildExit = $LASTEXITCODE
    if ($buildExit -ne 0) { throw "Folder-send build failed ($buildExit). See $LogRoot\build.log" }
    dotnet "$OutputRoot\tests\FolderSend.dll" "$OutputRoot\evidence" *> "$LogRoot\tests.log"
    $testExit = $LASTEXITCODE
    Get-Content "$LogRoot\tests.log" -Tail 5
    if ($testExit -ne 0) { throw "Folder-send checks failed ($testExit). See $LogRoot\tests.log" }
    Write-Output "Build and folder-send checks passed. Complete logs: $LogRoot"
} finally { Pop-Location }
