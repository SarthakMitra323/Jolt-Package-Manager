<#
.SYNOPSIS
    Cleanly removes Jolt (fast.exe) and its User PATH entry.
#>
$ErrorActionPreference = "Stop"

$installDir = Join-Path$env:LOCALAPPDATA "jolt\bin"
$targetExe  = Join-Path$installDir "fast.exe"

if (Test-Path $targetExe) {
    Remove-Item -Force $targetExe
    Write-Host "Removed $targetExe" -ForegroundColor Cyan
}

$currentUserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if (-not [string]::IsNullOrWhiteSpace($currentUserPath)) {
    $filtered =$currentUserPath.Split(';', [System.StringSplitOptions]::RemoveEmptyEntries) |
        Where-Object { $_.TrimEnd('\') -ine$installDir.TrimEnd('\') }
    [Environment]::SetEnvironmentVariable("Path", ($filtered -join ';'), "User")
    Write-Host "Removed $installDir from User PATH" -ForegroundColor Cyan
}

Write-Host "[OK] Jolt uninstalled cleanly." -ForegroundColor Green