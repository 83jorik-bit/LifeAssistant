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

$ActualRootRaw = (git -C $Root rev-parse --show-toplevel).Trim()
Assert-ExitCode "Resolve outer repository root"
$ActualRoot = [System.IO.Path]::GetFullPath($ActualRootRaw)
$ExpectedRoot = [System.IO.Path]::GetFullPath($Root)
if ($ActualRoot -ine $ExpectedRoot) {
    throw "Safety check failed: Git root is '$ActualRoot', expected '$Root'."
}

$Branch = (git -C $Root branch --show-current).Trim()
Assert-ExitCode "Read current branch"
if ($Branch -ne "main") {
    throw "Switch the outer repository to main before cleanup. Current branch: $Branch"
}

$InitialStatus = @(git -C $Root status --porcelain)
Assert-ExitCode "Check outer repository working tree"
# The only expected pre-cleanup item is the untracked nested repository itself.
$UnexpectedStatus = @($InitialStatus | Where-Object { $_ -notmatch '^\?\?\s+LifeAssistant/?$' })
if ($UnexpectedStatus.Count -gt 0) {
    Write-Host "Unexpected uncommitted changes were found in the outer repository:" -ForegroundColor Yellow
    $UnexpectedStatus | ForEach-Object { Write-Host $_ }
    throw "Commit or safely resolve outer-repository changes before cleanup. No deletion was performed."
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
# Decode the Russian verification phrases from Base64. This avoids Windows PowerShell 5.1
# misreading UTF-8 script literals when the script is stored without a UTF-8 BOM.
$DailyBudgetPhrase = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("0J7RgdGC0LDRgtC+0Log0LIg0L/RgNC10LTQtdC70LDRhSDQt9Cw0LTQsNC90L3Ri9GFINC70LjQvNC40YLQvtCy"))
$DailyGuidePhrase = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("0J7RgNC40LXQvdGC0LjRgA=="))
if ($Engine -notmatch "asksDaily" -or
    $Engine -notmatch "remainingByLimits" -or
    $Engine -notmatch [regex]::Escape($DailyBudgetPhrase) -or
    $Engine -notmatch [regex]::Escape($DailyGuidePhrase)) {
    throw "Approved offline daily-budget enhancement was not verified in the outer repository. No deletion was performed."
}

$Dispatcher = Get-Content $DispatcherPath -Raw -Encoding UTF8
$ConfirmationPhrase = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("0L/QvtC00YLQstC10YDQttC00LDRjg=="))
if ($Dispatcher -notmatch "NeedsConfirmation" -or
    $Dispatcher -notmatch "voice_action_pending" -or
    $Dispatcher -notmatch [regex]::Escape($ConfirmationPhrase)) {
    throw "Confirmation-based action flow was not verified. No deletion was performed."
}

$Context = Get-Content $ContextPath -Raw -Encoding UTF8
$AntiFabricationPhrase = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("0J3QuNC60L7Qs9C00LAg0L3QtSDRg9GC0LLQtdGA0LbQtNCw0Lk="))
$SuccessPhrase = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("0YPRgdC/0LXRiNC90L7Qs9C+INC00LXQudGB0YLQstC40Y8="))
if ($Context -notmatch [regex]::Escape($AntiFabricationPhrase) -or
    $Context -notmatch [regex]::Escape($SuccessPhrase)) {
    throw "Assistant anti-fabrication instruction was not verified. No deletion was performed."
}

# Do not touch outer local.properties, server/.env, server/data, or build outputs.
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
