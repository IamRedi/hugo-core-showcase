param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw "HUGO V2 documentation guard failed: $Message"
    }
}

function Read-RepositoryFile {
    param([string]$RelativePath)

    $path = Join-Path $RepositoryRoot $RelativePath
    Assert-True (Test-Path -LiteralPath $path -PathType Leaf) "missing $RelativePath"
    $content = Get-Content -Raw -LiteralPath $path
    Assert-True (-not [string]::IsNullOrWhiteSpace($content)) "empty $RelativePath"
    return $content
}

$acceptedT032Commit = "0bcf536cdaae3b5c9c22247bb093ac6fc68cff26"
$requiredDocuments = @(
    "README.md",
    "docs/HUGO_VISION.md",
    "docs/ARCHITECTURE_V2.md",
    "docs/UNIVERSAL_CAPABILITY_MODEL.md",
    "docs/DEVICE_BODY_MANIFEST.md",
    "docs/ADAPTER_ARCHITECTURE.md",
    "docs/TRUST_AND_PERMISSIONS.md",
    "docs/MEMORY_ARCHITECTURE_V2.md",
    "docs/CONTEXT_ENGINE.md",
    "docs/DEVELOPER_MODE.md",
    "docs/MIGRATION_PLAN_V2.md",
    "docs/ROADMAP_V2.md",
    "docs/HUGO_TASKS.md",
    "docs/HUGO_FUTURE_CONCEPTS.md",
    "docs/WORLD_MODEL_AND_AFFORDANCES.md",
    "docs/DECISIONS.md",
    "docs/SPARK_TASKS.md",
    "docs/T032_M2_ACCEPTANCE_RUN_2026-08-24_25.md",
    "docs/H001_ARCHITECTURE_ACCEPTANCE.md"
)

$contents = @{}
foreach ($document in $requiredDocuments) {
    $contents[$document] = Read-RepositoryFile $document
}

Push-Location $RepositoryRoot
try {
    & git cat-file -e "$acceptedT032Commit^{commit}"
    Assert-True ($LASTEXITCODE -eq 0) "accepted T032 commit is unavailable"

    & git merge-base --is-ancestor $acceptedT032Commit HEAD
    Assert-True ($LASTEXITCODE -eq 0) "HEAD does not descend from the accepted T032 checkpoint"
}
finally {
    Pop-Location
}

$brokenLinks = [System.Collections.Generic.List[string]]::new()
foreach ($document in $requiredDocuments) {
    $documentDirectory = Split-Path $document -Parent
    if ([string]::IsNullOrEmpty($documentDirectory)) {
        $documentDirectory = "."
    }

    $matches = [regex]::Matches($contents[$document], "\[[^\]]+\]\(([^)]+)\)")
    foreach ($match in $matches) {
        $target = $match.Groups[1].Value.Split("#")[0]
        if ([string]::IsNullOrEmpty($target) -or $target -match "^(https?:|mailto:)") {
            continue
        }

        $resolvedTarget = Join-Path $RepositoryRoot (Join-Path $documentDirectory $target)
        if (-not (Test-Path -LiteralPath $resolvedTarget)) {
            $brokenLinks.Add("$document -> $target")
        }
    }
}
Assert-True ($brokenLinks.Count -eq 0) ("broken relative links: " + ($brokenLinks -join ", "))

$taskMatches = [regex]::Matches($contents["docs/HUGO_TASKS.md"], "(?m)^\| (H\d{3}) \|")
$taskIds = @($taskMatches | ForEach-Object { $_.Groups[1].Value })
$expectedTaskIds = @(1..80 | ForEach-Object { "H{0:D3}" -f $_ })
Assert-True ($taskIds.Count -eq 80) "expected 80 H-task rows, found $($taskIds.Count)"
Assert-True (@(Compare-Object $expectedTaskIds $taskIds).Count -eq 0) "H-task IDs must be exactly H001-H080"
Assert-True (($taskIds | Sort-Object -Unique).Count -eq 80) "H-task IDs must be unique"

$legacyTaskMatches = [regex]::Matches(
    $contents["docs/MIGRATION_PLAN_V2.md"],
    "(?m)^\| (T0(?:3[3-9]|[4-5][0-9]|6[0-4])) \|"
)
$legacyTaskIds = @($legacyTaskMatches | ForEach-Object { $_.Groups[1].Value })
$expectedLegacyTaskIds = @(33..64 | ForEach-Object { "T{0:D3}" -f $_ })
Assert-True ($legacyTaskIds.Count -eq 32) "expected 32 T033-T064 migration rows, found $($legacyTaskIds.Count)"
Assert-True (@(Compare-Object $expectedLegacyTaskIds $legacyTaskIds).Count -eq 0) "migration matrix must cover exactly T033-T064"

foreach ($adrNumber in 11..19) {
    $adrId = "ADR-{0:D3}" -f $adrNumber
    $count = [regex]::Matches($contents["docs/DECISIONS.md"], "(?m)^## $adrId ").Count
    Assert-True ($count -eq 1) "$adrId must occur exactly once as a decision heading"
}

$invariants = @(
    @("docs/HUGO_VISION.md", "LOOI is Reference Body Adapter #1"),
    @("docs/ARCHITECTURE_V2.md", "No universal abstraction may bypass the LOOI safety controller"),
    @("docs/UNIVERSAL_CAPABILITY_MODEL.md", "A compatibility facade translates universal requests into that existing path"),
    @("docs/DEVICE_BODY_MANIFEST.md", "Manifests describe claims and compatibility metadata"),
    @("docs/TRUST_AND_PERMISSIONS.md", "Discovery, identity, trust, authentication, authorization, approval, safety, and execution are separate decisions"),
    @("docs/MEMORY_ARCHITECTURE_V2.md", "Memory belongs to Hugo's user-owned runtime"),
    @("docs/CONTEXT_ENGINE.md", "Context is not automatically memory"),
    @("docs/DEVELOPER_MODE.md", "Production runtime never edits, compiles, installs, or deploys itself"),
    @("docs/MIGRATION_PLAN_V2.md", $acceptedT032Commit),
    @("docs/HUGO_TASKS.md", "**Status:** DONE"),
    @("docs/HUGO_TASKS.md", "### H001 - Accept V2 baseline"),
    @("docs/WORLD_MODEL_AND_AFFORDANCES.md", "## Status and canonical ownership")
)
foreach ($invariant in $invariants) {
    Assert-True ($contents[$invariant[0]].Contains($invariant[1])) "missing invariant '$($invariant[1])' in $($invariant[0])"
}

Assert-True `
    ($contents["docs/SPARK_TASKS.md"] -match "(?s)### T032.+?- \*\*Status:\*\* DONE") `
    "T032 must remain DONE in docs/SPARK_TASKS.md"

Write-Output "HUGO V2 documentation guard: PASS"
Write-Output "Accepted T032 checkpoint: $acceptedT032Commit"
Write-Output "Required documents: $($requiredDocuments.Count)"
Write-Output "Backlog IDs: H001-H080 (80 unique)"
Write-Output "Migration rows: T033-T064 (32 complete)"
Write-Output "Architecture decisions: ADR-011-ADR-019"
