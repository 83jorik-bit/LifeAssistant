# Archive the original execution architecture document without depending on console/script code pages.
$ErrorActionPreference = "Stop"

$Root = "E:\GitHub\LifeAssistant"
$DestinationDir = Join-Path $Root "docs\archive"
$Destination = Join-Path $DestinationDir "EXECUTION_ARCHITECTURE_0.1_2026-11-14.md"

if (!(Test-Path (Join-Path $Root ".git"))) { throw "Git repository not found: $Root" }
$ActualRoot = [System.IO.Path]::GetFullPath((git -C $Root rev-parse --show-toplevel).Trim())
$ExpectedRoot = [System.IO.Path]::GetFullPath($Root)
if ($ActualRoot -ine $ExpectedRoot) { throw "Unexpected repository root: $ActualRoot" }

$Branch = (git -C $Root branch --show-current).Trim()
if ($Branch -ne "main") { throw "Expected branch main, found $Branch" }

$Head = (git -C $Root rev-parse HEAD).Trim()
$RemoteHead = (git -C $Root rev-parse origin/main).Trim()
if ($Head -ne $RemoteHead) { throw "Local main is not synchronized with origin/main. Run git pull --ff-only origin main first." }

# The original filename contains Cyrillic characters. Locate it by its stable ASCII suffix
# so Windows PowerShell 5.1 never has to decode a Cyrillic literal from this script.
$Candidates = @(Get-ChildItem -LiteralPath $Root -File -Filter "*LifeAssistant.txt")
if ($Candidates.Count -ne 1) {
    throw "Expected exactly one root-level *LifeAssistant.txt source document; found $($Candidates.Count). No files were moved."
}
$Source = $Candidates[0].FullName

if (Test-Path $Destination) { throw "Archive destination already exists: $Destination" }

$Length = (Get-Item -LiteralPath $Source).Length
if ($Length -ne 21874) { throw "Unexpected source file size ($Length bytes; expected 21874). Refusing to move." }

New-Item -ItemType Directory -Force -Path $DestinationDir | Out-Null
Move-Item -LiteralPath $Source -Destination $Destination

try {
    git -C $Root add -- "docs/archive/EXECUTION_ARCHITECTURE_0.1_2026-11-14.md"
    if ($LASTEXITCODE -ne 0) { throw "git add failed." }

    git -C $Root commit -m "Archive execution architecture baseline 0.1"
    if ($LASTEXITCODE -ne 0) { throw "git commit failed." }

    git -C $Root push origin main
    if ($LASTEXITCODE -ne 0) { throw "git push failed. The local commit exists; push it after resolving the remote issue." }
}
catch {
    Write-Host "Archiving/commit did not complete cleanly: $_" -ForegroundColor Yellow
    Write-Host "The source was moved to: $Destination" -ForegroundColor Yellow
    Write-Host "Do not delete it. Check git status and complete the commit manually." -ForegroundColor Yellow
    throw
}

Write-Host "Historical architecture 0.1 committed and pushed." -ForegroundColor Green
git -C $Root status --short --branch
