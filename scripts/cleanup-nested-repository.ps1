# Safe removal of the obsolete nested LifeAssistant Git repository.
# Run from any PowerShell location:
#   powershell -ExecutionPolicy Bypass -File E:\GitHub\LifeAssistant\scripts\cleanup-nested-repository.ps1

$ErrorActionPreference = "Stop"

$Root = "E:\GitHub\LifeAssistant"
$Nested = Join-Path $Root "LifeAssistant"
$Backup = "E:\LifeAssistant_AuditBackup_20261114"

function Assert-ExitCode([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed (exit code $LASTEXITCODE). No deletion was performed after this failure."
    }
}

if (!(Test-Path (Join-Path $Root ".git"))) {
    throw "Outer Git repository not found at $Root"
}

$ActualRoot = (git -C $Root rev-parse --show-toplevel).Trim().TrimEnd("\")
Assert-ExitCode "Resolve outer repository root"
if ($ActualRoot -ne $Root.TrimEnd("\")) {
    throw "Safety check failed: Git root is '$ActualRoot', expected '$Root'."
}

$Branch = (git -C $Root branch --show-current).Trim()
Assert-ExitCode "Read current branch"
if ($Branch -ne "main") {
    throw "Switch the outer repository to main before cleanup. Current branch: $Branch"
}

if (!(Test-Path $Nested)) {
    Write-Host "Nested directory is already absent. Nothing to remove." -ForegroundColor Green
    exit 0
}

if (!(Test-Path (Join-Path $Nested ".git"))) {
    throw "The target exists but is not identified as the expected nested Git repository. Refusing to delete it."
}

if (!(Test-Path $Backup)) {
    throw "Expected audit backup not found at $Backup. Verify the backup before deleting the nested repository."
}

$RequiredBackupFiles = @(
    "LocalAssistantEngine.kt",
    "LocalAssistantEngine.kt.bak",
    "LocalAssistantEngine.patch",
    "package-lock.json"
)
foreach ($Name in $RequiredBackupFiles) {
    if (!(Test-Path (Join-Path $Backup $Name))) {
        throw "Backup is incomplete: missing $Name. No deletion was performed."
    }
}

Write-Host "Updating the outer repository from origin/main..." -ForegroundColor Cyan
git -C $Root fetch origin
Assert-ExitCode "Fetch origin"
git -C $Root pull --ff-only origin main
Assert-ExitCode "Fast-forward outer main"

$EnginePath = Join-Path $Root "app\src\main\java\com\lifenavigator\app\data\ai\LocalAssistantEngine.kt"
$DispatcherPath = Join-Path $Root "app\src\main\java\com\lifenavigator\app\data\ai\VoiceCommandDispatcher.kt"
$ContextPath = Join-Path $Root "app\src\main\java\com\lifenavigator\app\data\ai\AssistantContext.kt"

foreach ($Path in @($EnginePath, $DispatcherPath, $ContextPath)) {
    if (!(Test-Path $Path)) {
        throw "Required current source file not found: $Path"
    }
}

$Engine = Get-Content $EnginePath -Raw -Encoding UTF8
if ($Engine -notmatch "Остаток в пределах заданных лимитов" -or
    $Engine -notmatch "Ориентир" -or
    $Engine -notmatch "asksDaily") {
    throw "Approved offline daily-budget enhancement was not verified in the outer repository. No deletion was performed."
}

$Dispatcher = Get-Content $DispatcherPath -Raw -Encoding UTF8
if ($Dispatcher -notmatch "NeedsConfirmation" -or
    $Dispatcher -notmatch "подтверждаю") {
    throw "Confirmation-based action flow was not verified. No deletion was performed."
}

$Context = Get-Content $ContextPath -Raw -Encoding UTF8
if ($Context -notmatch "Никогда не утверждай" -or
    $Context -notmatch "успешного действия") {
    throw "Assistant anti-fabrication instruction was not verified. No deletion was performed."
}

# Do not touch the outer repository's local.properties, server/.env, server/data,
# build outputs, or other local configuration/data. Only the nested directory is removed.
Write-Host ""
Write-Host "Verified retained functionality:" -ForegroundColor Green
Write-Host " - approved asksDaily financial guidance exists in outer LocalAssistantEngine.kt"
Write-Host " - confirmation-based task/calendar action flow exists in VoiceCommandDispatcher.kt"
Write-Host " - anti-fabrication instruction exists in AssistantContext.kt"
Write-Host " - audit backup exists at $Backup"
Write-Host ""
Write-Host "The following directory will be permanently removed:" -ForegroundColor Yellow
Write-Host "  $Nested" -ForegroundColor Yellow
Write-Host "The outer repository and its local configuration/data will not be removed."

$Confirmation = Read-Host "Type DELETE NESTED REPOSITORY to continue"
if ($Confirmation -cne "DELETE NESTED REPOSITORY") {
    Write-Host "Cancelled. No files were deleted." -ForegroundColor Yellow
    exit 0
}

Remove-Item -LiteralPath $Nested -Recurse -Force

if (Test-Path $Nested) {
    throw "Removal did not complete. Inspect $Nested manually."
}

Write-Host ""
Write-Host "Nested repository removed." -ForegroundColor Green
Write-Host "Final outer repository status:"
git -C $Root status --short --branch
Assert-ExitCode "Read final Git status"
