# Stops OutageGuard, removes it from autostart and deletes ~/.outageguard.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\uninstall-windows.ps1 [-KeepConfig]
#   -KeepConfig  keep config.properties (your group and settings) for a later reinstall
param([switch]$KeepConfig)
$ErrorActionPreference = "Stop"
$dir = "$env:USERPROFILE\.outageguard"

if (Get-ScheduledTask -TaskName "OutageGuard" -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName "OutageGuard"
    Unregister-ScheduledTask -TaskName "OutageGuard" -Confirm:$false
    Write-Host "Removed scheduled task."
}

# Exe mode runs as OutageGuard.exe; jar mode as java/javaw with outageguard.jar on the command line.
Get-Process OutageGuard -ErrorAction SilentlyContinue | Stop-Process -Force
Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
    Where-Object { $_.CommandLine -like "*outageguard.jar*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep -Seconds 2

# Notification identity (shows "OutageGuard" as the toast title).
Remove-Item "HKCU:\Software\Classes\AppUserModelId\OutageGuard" -Recurse -ErrorAction SilentlyContinue

$shortcut ="$([Environment]::GetFolderPath('Startup'))\OutageGuard.lnk"
if (Test-Path $shortcut) {
    Remove-Item $shortcut
    Write-Host "Removed Startup folder shortcut."
}

if (Test-Path $dir) {
    if ($KeepConfig) {
        Get-ChildItem $dir -Force | Where-Object { $_.Name -ne "config.properties" } | Remove-Item -Recurse -Force
        Write-Host "Deleted $dir (kept config.properties)."
    } else {
        Remove-Item -Recurse -Force $dir
        Write-Host "Deleted $dir."
    }
}
Write-Host "OutageGuard uninstalled."
