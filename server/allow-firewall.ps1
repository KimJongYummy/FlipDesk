# Allows FlipDesk through Windows Defender Firewall on PRIVATE networks only (home Wi-Fi).
# Right-click > "Run with PowerShell" (it asks for admin once). Safe to run again.
param([int]$Port = 8765, [int]$DiscoveryPort = 8766)
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Port $Port -DiscoveryPort $DiscoveryPort"
    exit
}
Get-NetFirewallRule -DisplayName "FlipDesk*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName "FlipDesk (TCP $Port)" -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private | Out-Null
New-NetFirewallRule -DisplayName "FlipDesk discovery (UDP $DiscoveryPort)" -Direction Inbound -Protocol UDP -LocalPort $DiscoveryPort -Action Allow -Profile Private | Out-Null
Write-Host "FlipDesk is allowed on private networks (TCP $Port, UDP $DiscoveryPort)." -ForegroundColor Green
# The rules only apply on Private networks, so offer to switch the current home network over.
foreach ($p in Get-NetConnectionProfile | Where-Object NetworkCategory -eq 'Public') {
    $a = Read-Host "'$($p.Name)' on $($p.InterfaceAlias) is set to Public, so FlipDesk is blocked there. Is this your home network? Switch it to Private? (y/n)"
    if ($a -match '^[yY]') {
        Set-NetConnectionProfile -InterfaceIndex $p.InterfaceIndex -NetworkCategory Private
        Write-Host "$($p.InterfaceAlias) is now Private." -ForegroundColor Green
    }
}
Read-Host "Press Enter to close"
