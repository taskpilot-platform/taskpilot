# PowerShell script to run focused heuristic correctness tests
param(
    [string]$TestPattern = "ScoreRangeTest,HeuristicStrategyTest"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$RepoRoot = Resolve-Path "$ScriptDir\..\..\..\.."

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Running Focused Heuristic Tests: $TestPattern" -ForegroundColor Cyan
Write-Host " Working Directory: $RepoRoot" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

Push-Location $RepoRoot
try {
    $mvnCmd = ".\mvnw.cmd"
    if (-not (Test-Path $mvnCmd)) {
        throw "Maven wrapper .\mvnw.cmd not found at repository root: $RepoRoot"
    }

    $mvnArgs = @("test", "-pl", "taskpilot-ai", "-Dtest=$TestPattern", "-DfailIfNoTests=false")
    Write-Host "Executing: $mvnCmd $($mvnArgs -join ' ')" -ForegroundColor Yellow
    
    & $mvnCmd $mvnArgs
    $exitCode = $LASTEXITCODE

    if ($exitCode -eq 0) {
        Write-Host "`n[SUCCESS] Focused heuristic tests passed cleanly." -ForegroundColor Green
    } else {
        Write-Host "`n[FAILURE] Focused heuristic tests failed with exit code: $exitCode" -ForegroundColor Red
    }
    exit $exitCode
} finally {
    Pop-Location
}
