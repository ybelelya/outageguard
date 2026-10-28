# Registers OutageGuard to start (without console window) when you log in.
# Usage (from the project folder):
#   jar mode (needs Java installed):   .\gradlew.bat build
#                                      powershell -ExecutionPolicy Bypass -File scripts\install-windows.ps1
#   exe mode (bundled Java runtime):   .\gradlew.bat packageApp
#                                      powershell -ExecutionPolicy Bypass -File scripts\install-windows.ps1 -Exe
param(
    [switch]$Exe,
    [string]$Jar = "$PSScriptRoot\..\build\libs\outageguard.jar",
    [string]$AppImage = "$PSScriptRoot\..\build\dist\OutageGuard"
)
$ErrorActionPreference = "Stop"
$home_ = "$env:USERPROFILE\.outageguard"
New-Item -ItemType Directory -Force $home_ | Out-Null

# Stop a running instance so its files can be replaced.
if (Get-ScheduledTask -TaskName "OutageGuard" -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName "OutageGuard"
}
Get-Process OutageGuard -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2

if ($Exe) {
    $target = "$home_\app"
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Copy-Item -Recurse $AppImage $target
    $action = New-ScheduledTaskAction -Execute "$target\OutageGuard.exe"
} else {
    $target = "$home_\outageguard.jar"
    Copy-Item $Jar $target -Force
    $javaw = (Get-Command javaw.exe).Source
    $action = New-ScheduledTaskAction -Execute $javaw -Argument "-jar `"$target`""
}

$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName "OutageGuard" -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName "OutageGuard"
Write-Host "OutageGuard installed ($(if ($Exe) { 'exe' } else { 'jar' }) mode) and started."
Write-Host "Remove with: Unregister-ScheduledTask OutageGuard -Confirm:`$false"
