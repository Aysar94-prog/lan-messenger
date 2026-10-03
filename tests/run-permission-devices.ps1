param([string]$JdkRoot='C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [string]$AndroidJar='C:\Program Files (x86)\Android\android-sdk\platforms\android-34\android.jar',
  [string]$Classes='')
$ErrorActionPreference='Stop'
if(!$Classes){$Classes=(Get-ChildItem 'D:\LAN-Messenger\outputs\.build\android-voice' -Directory -Filter 'classes-*' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName}
if(!$Classes){throw 'Build the current Android source first or pass -Classes.'}
$taskOutput=Join-Path 'D:\LAN-Messenger\outputs\.build' ('permission-check-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskOutput | Out-Null
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -cp "$Classes;$AndroidJar" -d $taskOutput "$PSScriptRoot\TestProtector.java" "$PSScriptRoot\PermissionDevicesCheck.java"
if($LASTEXITCODE -ne 0){throw 'Permission test compile failed'}
& "$JdkRoot\bin\java.exe" -cp "$taskOutput;$Classes" net.lanmsg.chat.PermissionDevicesCheck "$taskOutput\state"
if($LASTEXITCODE -ne 0){throw 'Permission tests failed'}
