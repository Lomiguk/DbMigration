# Full Migration Cycle Script
# Usage: .\run-migration.ps1 [-Count 100000] [-SkipGenerate] [-SkipSync] [-SkipValidate] [-Analyze] [-MigrateIndexes]

[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

param(
    [int]$Count = 100000,
    [switch]$SkipGenerate,
    [switch]$SkipSync,
    [switch]$SkipValidate,
    [switch]$DryRun,
    [switch]$Analyze,
    [switch]$MigrateIndexes,
    [switch]$CreateFkIndexes,
    [string]$LogFile = "migration_$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss').log"
)

$ErrorActionPreference = "Stop"

Start-Transcript -Path $LogFile -Append
Write-Host "[i] Logging to file: $LogFile" -ForegroundColor Cyan

function Run-Command {
    param([string]$CmdArgs, [string]$Label)
    Write-Host "`n============================================================" -ForegroundColor Cyan
    Write-Host "  $Label" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "Command: migrate $CmdArgs" -ForegroundColor Gray
    Write-Host ""

    # Временно меняем preference, чтобы предупреждения Java (stderr) не прерывали скрипт
    $tempErrPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"

    # Запускаем команду и перехватываем весь вывод
    & ./gradlew.bat run --args="$CmdArgs"

    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $CmdArgs"
    }

    # Возвращаем исходную настройку
    $ErrorActionPreference = $tempErrPref

    # Show relevant lines (skip Gradle noise and Hikari debug)
    $relevantLines = $output -split "`n" | Where-Object {
        $_ -match '^\[' -or
        $_ -match '^\+' -or
        $_ -match '^\|' -or
        $_ -match '(PASSED|FAILED|Total|Error|rows|Speed|Sync:|VALID|INVALID|Generation|Migration|Truncat|Preload|Schema|order)' -or
        $_ -match '^\[i\]|^\[\+\]|^\[x\]|^\[!\]' -or
        $_ -match 'BUILD (SUCCESSFUL|FAILED)' -or
        $_ -match 'actionable tasks'
    } | ForEach-Object { $_.Trim() }

    if ($relevantLines) {
        $relevantLines | ForEach-Object { Write-Host $_ }
    } else {
        # If filter caught nothing, show last 20 lines
        Write-Host "[i] Full output (filter caught nothing):" -ForegroundColor Yellow
        ($output -split "`n" | Select-Object -Last 20) | ForEach-Object { Write-Host $_ }
    }
}

# ============================================================
# STEP 0: Check prerequisites
# ============================================================
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  PostgreSQL UUID -> BIGINT Migration" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "[i] Checking Docker..." -ForegroundColor Yellow
$dockerVersion = docker --version 2>$null
if (-not $dockerVersion) {
    Write-Host "[x] Docker is not installed or not in PATH." -ForegroundColor Red
    exit 1
}
Write-Host "[+] Docker found: $dockerVersion" -ForegroundColor Green

Write-Host "[i] Checking Docker daemon..." -ForegroundColor Yellow
$dockerInfo = docker info 2>$null
if (-not $dockerInfo) {
    Write-Host "[x] Docker daemon is not running. Start Docker Desktop first." -ForegroundColor Red
    exit 1
}
Write-Host "[+] Docker daemon is running" -ForegroundColor Green

Write-Host "[i] Checking containers..." -ForegroundColor Yellow
$containers = docker ps --format "{{.Names}}" 2>$null
if ($containers -notcontains "source_db" -or $containers -notcontains "target_db") {
    Write-Host "[!] Containers not found. Starting docker-compose..." -ForegroundColor Yellow
    docker-compose up -d
    Start-Sleep -Seconds 10
}
Write-Host "[+] Containers are running" -ForegroundColor Green

Write-Host "[i] Checking source_db schema..." -ForegroundColor Yellow
$tableCountRaw = docker exec source_db psql -U user -d source_db -t -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'" 2>$null
$tableCount = ($tableCountRaw | Out-String).Trim() -as [int]
if (-not $tableCount -or $tableCount -lt 20) {
    Write-Host "[!] Schema not initialized. Running init.sql..." -ForegroundColor Yellow
    Get-Content init.sql | docker exec -i source_db psql -U user -d source_db
}
Write-Host "[+] Source schema ready ($tableCount tables)" -ForegroundColor Green

# ============================================================
# STEP 0.5: Generate test data if source is empty
# ============================================================
Write-Host "[i] Checking source data..." -ForegroundColor Yellow
$recordCount = docker exec source_db psql -U user -d source_db -t -c "SELECT COUNT(*) FROM users" 2>$null
$records = ($recordCount | Out-String).Trim() -as [int]
if (-not $records -or $records -eq 0) {
    Write-Host "[!] Source database is empty. Generating $Count test records..." -ForegroundColor Yellow
    Run-Command -CmdArgs "generate-data --count $Count" -Label "STEP 0: Generate Test Data ($Count records)"
} else {
    Write-Host "[+] Source database has $records users records" -ForegroundColor Green
}

# ============================================================
# STEP 1: Initial Migration (copy)
# ============================================================
$copyArgs = "copy"
if ($DryRun) { $copyArgs += " --dry-run" }
if ($MigrateIndexes) { $copyArgs += " --migrate-indexes" }
if ($CreateFkIndexes) { $copyArgs += " --create-fk-indexes" }

if ($MigrateIndexes) {
    $indexLabel = " + перенос индексов из source"
} elseif ($CreateFkIndexes) {
    $indexLabel = " + создание FK-индексов"
} else {
    $indexLabel = " (без индексов)"
}

Run-Command -CmdArgs $copyArgs -Label "STEP 1: Initial Data Migration$indexLabel"

# ============================================================
# STEP 2: Generate new data in source (simulate live traffic)
# ============================================================
if (-not $SkipGenerate) {
    $genCount = [math]::Max(1000, [int]($Count * 0.01))  # 1% of initial
    Run-Command -CmdArgs "generate-data --count $genCount" -Label "STEP 2: Add New Data to Source ($genCount records)"
} else {
    Write-Host "`n[i] Skipping data generation (-SkipGenerate)" -ForegroundColor Yellow
}

# ============================================================
# STEP 3: Delta Sync (migrate only new records)
# ============================================================
if (-not $SkipSync) {
    Run-Command -CmdArgs "sync" -Label "STEP 3: Delta Sync"
} else {
    Write-Host "`n[i] Skipping sync (-SkipSync)" -ForegroundColor Yellow
}

# ============================================================
# STEP 4: Validate data integrity
# ============================================================
if (-not $SkipValidate) {
    Run-Command -CmdArgs "validate" -Label "STEP 4: Data Integrity Validation"
} else {
    Write-Host "`n[i] Skipping validation (-SkipValidate)" -ForegroundColor Yellow
}

# ============================================================
# STEP 5: Final Status
# ============================================================
Run-Command -CmdArgs "status" -Label "STEP 5: Final Status"

# ============================================================
# STEP 6: Performance Analysis (optional)
# ============================================================
if ($Analyze) {
    Write-Host "`n============================================================" -ForegroundColor Cyan
    Write-Host "  STEP 6: Performance Analysis" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan

    if (Test-Path "analyze-migration.py") {
        python analyze-migration.py --charts --json
    } else {
        Write-Host "[x] analyze-migration.py not found" -ForegroundColor Red
    }
}

# ============================================================
# Summary
# ============================================================
Write-Host "`n============================================================" -ForegroundColor Green
Write-Host "  Migration Cycle Complete" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Source: jdbc:postgresql://localhost:5431/source_db" -ForegroundColor Gray
Write-Host "  Target: jdbc:postgresql://localhost:5432/target_db" -ForegroundColor Gray

if ($MigrateIndexes) {
    Write-Host "  Indexes: перенесены из source" -ForegroundColor Green
} elseif ($CreateFkIndexes) {
    Write-Host "  Indexes: FK-индексы созданы" -ForegroundColor Yellow
} else {
    Write-Host "  Indexes: не переносятся (по умолчанию)" -ForegroundColor Gray
}

Write-Host ""
Write-Host "  Next steps:" -ForegroundColor Yellow
Write-Host "    ./gradlew run --args='status'     - Check status" -ForegroundColor Gray
Write-Host "    ./gradlew run --args='validate'   - Validate integrity" -ForegroundColor Gray
Write-Host "    ./gradlew run --args='sync'       - Sync new data" -ForegroundColor Gray
Write-Host ""

Stop-Transcript