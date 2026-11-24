<#
.SYNOPSIS
    Secure user-space installer for Jolt (fast).
    - No Admin privileges required (installs to %LOCALAPPDATA%\jolt\bin)
    - Enforces TLS 1.2+ and mandatory SHA-256 verification for remote downloads
    - Adds %LOCALAPPDATA%\jolt\bin to User PATH cleanly without duplicates
#>
param(
    [string]$LocalBinary = "",
    [string]$Version = "latest",
    [string]$Repo = "SarthakMitra323/Jolt-Package-Manager"
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$installDir = Join-Path $env:LOCALAPPDATA "jolt\bin"
$targetExe  = Join-Path $installDir "fast.exe"
$assetName  = "fast-windows-x64.exe"
$tmpDir     = Join-Path ([System.IO.Path]::GetTempPath()) ("jolt-install-" + [Guid]::NewGuid().ToString("N"))

try {
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
    $stagedExe = Join-Path $tmpDir "fast.exe"

    if (-not [string]::IsNullOrWhiteSpace($LocalBinary)) {
        $resolvedLocal = (Resolve-Path $LocalBinary -ErrorAction Stop).Path
        if (-not (Test-Path $resolvedLocal -PathType Leaf)) {
            throw "Local binary not found: $LocalBinary"
        }
        Write-Host "[1/3] Staging local binary: $resolvedLocal" -ForegroundColor Cyan
        Copy-Item -Path $resolvedLocal -Destination $stagedExe -Force
        $localHash = (Get-FileHash -Path $stagedExe -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Host "      SHA-256: $localHash" -ForegroundColor DarkGray
    } else {
        $baseUrl = if ($Version -eq "latest") {
            "https://github.com/$Repo/releases/latest/download"
        } else {
            "https://github.com/$Repo/releases/download/$Version"
        }

        $exeUrl  = "$baseUrl/$assetName"
        $sumsUrl = "$baseUrl/SHA256SUMS.txt"
        $tmpSums = Join-Path $tmpDir "SHA256SUMS.txt"

        Write-Host "[1/3] Downloading Jolt ($Version) and SHA256SUMS.txt from GitHub Releases..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $exeUrl  -OutFile $stagedExe -UseBasicParsing
        Invoke-WebRequest -Uri $sumsUrl -OutFile $tmpSums   -UseBasicParsing

        Write-Host "[2/3] Verifying cryptographic SHA-256 integrity..." -ForegroundColor Cyan
        $expectedLine = Get-Content $tmpSums | Where-Object { $_ -match "\b$([regex]::Escape($assetName))\s*$" } | Select-Object -First 1
        if (-not $expectedLine) {
            throw "Security check failed: $assetName entry missing from SHA256SUMS.txt."
        }
        $expectedHash = ($expectedLine.Trim() -split "\s+")[0].ToLowerInvariant()
        $actualHash   = (Get-FileHash -Path $stagedExe -Algorithm SHA256).Hash.ToLowerInvariant()

        if ($actualHash -ne $expectedHash) {
            throw "SECURITY ABORT: SHA-256 mismatch!`n  Expected: $expectedHash`n  Actual:   $actualHash"
        }
        Write-Host "      SHA-256 verified: $actualHash" -ForegroundColor Green
    }

    # Install into user-scoped directory (no elevation needed)
    New-Item -ItemType Directory -Force -Path $installDir | Out-Null
    Move-Item -Path $stagedExe -Destination $targetExe -Force
    Unblock-File -Path $targetExe -ErrorAction SilentlyContinue

    # Idempotently update User PATH
    Write-Host "[3/3] Registering $installDir in User PATH..." -ForegroundColor Cyan
    $currentUserPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = @()
    if (-not [string]::IsNullOrWhiteSpace($currentUserPath)) {
        $entries = $currentUserPath.Split(';', [System.StringSplitOptions]::RemoveEmptyEntries)
    }

    $alreadyInPath = $entries | Where-Object { $_.TrimEnd('\') -ieq $installDir.TrimEnd('\') }
    if (-not $alreadyInPath) {
        $newUserPath = (($entries + $installDir) -join ';')
        [Environment]::SetEnvironmentVariable("Path", $newUserPath, "User")
    }

    # Make 'fast' available immediately in the current PowerShell session
    if (($env:Path.Split(';') | Where-Object { $_.TrimEnd('\') -ieq $installDir.TrimEnd('\') }).Count -eq 0) {
        $env:Path = "$installDir;$env:Path"
    }

    Write-Host "`n[OK] Jolt installed securely to $targetExe" -ForegroundColor Green
    Write-Host "     Run 'fast --help' to get started (open a new terminal if using cmd/other shells)." -ForegroundColor White
} finally {
    if (Test-Path $tmpDir) {
        Remove-Item -Recurse -Force $tmpDir -ErrorAction SilentlyContinue
    }
}