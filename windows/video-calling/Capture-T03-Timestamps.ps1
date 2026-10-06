# Capture helper for WVC-T03 real audio test
# Run on each device during the call to log timestamps and state.

param(
  [string]$OutDir = "D:\LAN-Messenger\source\outputs\wvc-t03-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
)

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff')
"Start: $stamp" | Out-File (Join-Path $OutDir 'capture-start.txt')
"Recording to $OutDir"
"Use this to note events: Call initiated, INVITE sent, Ringing, Answered, Connected, Hangup"
"Stop with Ctrl+C when done"
