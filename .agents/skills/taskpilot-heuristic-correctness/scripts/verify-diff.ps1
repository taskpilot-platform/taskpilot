# PowerShell script to audit working tree diff, inspect tracked & untracked files, and enforce strict scope allowlists
param(
    [string[]]$AllowedTrackedPrefixes = @(
        ".agents/skills/taskpilot-heuristic-correctness/",
        "taskpilot-ai/src/main/java/com/taskpilot/ai/heuristic/",
        "taskpilot-ai/src/test/java/com/taskpilot/ai/heuristic/"
    ),
    [string[]]$AllowedUntrackedPrefixes = @(
        ".agents/skills/taskpilot-heuristic-correctness/",
        "taskpilot-ai/src/test/java/com/taskpilot/ai/heuristic/"
    )
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$RepoRoot = Resolve-Path "$ScriptDir\..\..\..\.."

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Auditing Working Tree & Scope Allowlist" -ForegroundColor Cyan
Write-Host " Working Directory: $RepoRoot" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

Push-Location $RepoRoot
try {
    Write-Host "`n--- 1. Git Status ---" -ForegroundColor Yellow
    git status --short

    Write-Host "`n--- 2. Tracked Changes (Unstaged) ---" -ForegroundColor Yellow
    git diff --stat
    $unstagedFiles = git diff --name-only

    Write-Host "`n--- 3. Staged Changes (Cached) ---" -ForegroundColor Yellow
    git diff --cached --stat
    $stagedFiles = git diff --cached --name-only

    Write-Host "`n--- 4. Untracked Files ---" -ForegroundColor Yellow
    $untrackedFiles = git ls-files --others --exclude-standard
    if ($untrackedFiles) {
        $untrackedFiles | ForEach-Object { Write-Host "  ?? $_" }
    } else {
        Write-Host "  (none)"
    }

    Write-Host "`n--- 5. Formatting & Whitespace Audit ---" -ForegroundColor Yellow
    git diff --check
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] git diff --check reported whitespace or formatting errors." -ForegroundColor Red
        exit 1
    }
    git diff --cached --check
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] git diff --cached --check reported whitespace or formatting errors." -ForegroundColor Red
        exit 1
    }

    # Normalize file paths with forward slashes
    $trackedAll = @($unstagedFiles) + @($stagedFiles) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Replace('\', '/') }
    $untrackedAll = @($untrackedFiles) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Replace('\', '/') }

    Write-Host "`n--- 6. Scope Budget & Allowlist Audit ---" -ForegroundColor Yellow
    $scopeViolations = @()

    # Verify tracked modifications against allowed tracked prefixes
    foreach ($file in $trackedAll) {
        $matched = $false
        foreach ($prefix in $AllowedTrackedPrefixes) {
            $normPrefix = $prefix.Replace('\', '/')
            if ($file.StartsWith($normPrefix)) {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            $scopeViolations += "Tracked file outside allowlist: $file"
        }
    }

    # Verify untracked files against allowed untracked prefixes
    foreach ($file in $untrackedAll) {
        $matched = $false
        foreach ($prefix in $AllowedUntrackedPrefixes) {
            $normPrefix = $prefix.Replace('\', '/')
            if ($file.StartsWith($normPrefix)) {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            $scopeViolations += "Untracked file outside allowlist: $file"
        }
    }

    Write-Host "`n--- 7. Secret & Artifact Pattern Audit ---" -ForegroundColor Yellow
    $allFiles = @($trackedAll) + @($untrackedAll)
    
    # Supplementary check for secret-like filenames, artifacts, migrations, frontend, and app configs
    $forbiddenRegexes = @(
        '(^|/)\.env(\..+)?$',
        '(?i)token',
        '(?i)credential',
        '(?i)password',
        '(?i)secret',
        '(?i)private-key',
        '\.pem$',
        '\.key$',
        '\.p12$',
        '\.jks$',
        '(?i)live_uat',
        '(^|/)scratch/',
        '(^|/)target/',
        '\.class$',
        '\.jar$',
        'db/migration',
        'taskpilot-frontend',
        'application.*\.ya?ml$',
        'application.*\.properties$'
    )

    $patternViolations = @()
    foreach ($file in $allFiles) {
        foreach ($regex in $forbiddenRegexes) {
            if ($file -match $regex) {
                $patternViolations += "File '$file' matched forbidden pattern: $regex"
                break
            }
        }
    }

    # Report results
    $hasFailure = $false
    if ($scopeViolations.Count -gt 0) {
        Write-Host "`n[FAIL] Scope Budget Violations Detected:" -ForegroundColor Red
        $scopeViolations | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
        $hasFailure = $true
    }

    if ($patternViolations.Count -gt 0) {
        Write-Host "`n[FAIL] Forbidden Secret/Artifact Patterns Detected:" -ForegroundColor Red
        $patternViolations | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
        $hasFailure = $true
    }

    if ($hasFailure) {
        Write-Host "`n[FAIL] Working tree verification failed scope or pattern checks." -ForegroundColor Red
        exit 1
    }

    Write-Host "`n[SUCCESS] Diff quality, untracked coverage, secret checks, and scope allowlists PASSED." -ForegroundColor Green
    exit 0
} finally {
    Pop-Location
}
