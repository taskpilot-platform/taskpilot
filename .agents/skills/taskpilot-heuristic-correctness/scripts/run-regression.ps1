# PowerShell script to run affected module regression tests
param(
    [string]$Module = "taskpilot-ai"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$RepoRoot = Resolve-Path "$ScriptDir\..\..\..\.."

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Running Regression Suite for Module: $Module" -ForegroundColor Cyan
Write-Host " Working Directory: $RepoRoot" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

Push-Location $RepoRoot
try {
    $mvnCmd = ".\mvnw.cmd"
    if (-not (Test-Path $mvnCmd)) {
        throw "Maven wrapper .\mvnw.cmd not found at repository root: $RepoRoot"
    }

    # Run tests in offline mode (-o) to ensure no external network or docker dependencies
    $mvnArgs = @("test", "-pl", $Module, "-o")
    Write-Host "Executing: $mvnCmd $($mvnArgs -join ' ')" -ForegroundColor Yellow
    
    & $mvnCmd $mvnArgs
    $exitCode = $LASTEXITCODE

    if ($exitCode -eq 0) {
        Write-Host "`n[SUCCESS] Module regression suite passed cleanly." -ForegroundColor Green
    } else {
        Write-Host "`n[FAILURE] Module regression suite failed with exit code: $exitCode" -ForegroundColor Red
    }
    exit $exitCode
} finally {
    Pop-Location
}
