param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "HUGO First Life documentation guard failed: $Message" }
}

$planPath = Join-Path $RepositoryRoot "docs/HUGO_FIRST_LIFE.md"
$tasksPath = Join-Path $RepositoryRoot "docs/HUGO_FIRST_LIFE_TASKS.md"
$decisionPath = Join-Path $RepositoryRoot "docs/FL001_SLICE_CONTRACT_FEASIBILITY.md"
foreach ($path in @($planPath, $tasksPath, $decisionPath)) {
    Assert-True (Test-Path -LiteralPath $path -PathType Leaf) "missing $path"
}

$plan = Get-Content -Raw -Encoding UTF8 -LiteralPath $planPath
$tasks = Get-Content -Raw -Encoding UTF8 -LiteralPath $tasksPath
$decision = Get-Content -Raw -Encoding UTF8 -LiteralPath $decisionPath

$expectedIds = 1..15 | ForEach-Object { "FL{0:D3}" -f $_ }
$indexIds = [regex]::Matches($tasks, '(?m)^\| (FL\d{3}) \|') |
    ForEach-Object { $_.Groups[1].Value }
$sectionIds = [regex]::Matches($tasks, '(?m)^## (FL\d{3}) -') |
    ForEach-Object { $_.Groups[1].Value }
Assert-True (($indexIds -join ',') -eq ($expectedIds -join ',')) "ordered index must contain FL001-FL015 exactly once"
Assert-True (($sectionIds -join ',') -eq ($expectedIds -join ',')) "task sections must contain FL001-FL015 in order"

$expectedDependencies = [ordered]@{
    FL001 = 'H035'
    FL002 = 'FL001'
    FL003 = 'FL001'
    FL004 = 'FL001'
    FL005 = 'FL001'
    FL006 = 'FL005'
    FL007 = 'FL006'
    FL008 = 'FL002, FL003, FL005, FL006'
    FL009 = 'FL003, FL004'
    FL010 = 'FL003, FL004'
    FL011 = 'FL003, FL004'
    FL012 = 'FL011'
    FL013 = 'FL007, FL008, FL012'
    FL014 = 'FL002-FL013'
    FL015 = 'FL014'
}
foreach ($entry in $expectedDependencies.GetEnumerator()) {
    $pattern = '(?m)^\| ' + [regex]::Escape($entry.Key) + ' \|[^\r\n]*\| ' +
        [regex]::Escape($entry.Value) + ' \|'
    Assert-True ([regex]::IsMatch($tasks, $pattern)) "dependency mismatch for $($entry.Key)"
}

$fl001Section = [regex]::Match($tasks, '(?s)## FL001 -.*?(?=## FL002 -)').Value
$fl002Section = [regex]::Match($tasks, '(?s)## FL002 -.*?(?=## FL003 -)').Value
Assert-True ($fl001Section.Contains('**Status:** `DONE`')) "FL001 must remain DONE"
Assert-True ($fl002Section.Contains('**Status:** `DONE`')) "FL002 must be DONE after device acceptance"
Assert-True ($tasks.Contains('H036-H080 remain canonical and are tracked separately')) `
    "canonical backlog progress must remain separate from the frozen First Life slice"
Assert-True ($tasks.Contains('H036 was unstarted at that slice-freeze boundary')) `
    "FL015 must preserve its historical H036 stop"
Assert-True ($plan.Contains('Later canonical work is tracked in `HUGO_TASKS.md`')) `
    "plan must delegate later canonical status to the canonical backlog"
Assert-True ($decision.Contains('No production Kotlin, manifest, dependency, model, permission, runtime path, or T032 evidence was')) `
    "FL001 frozen boundary statement missing"
Assert-True ($tasks.Contains('Do not alter canonical HUGO task numbering') -or $tasks.Contains('do not alter canonical HUGO task numbering')) `
    "canonical backlog separation missing"

Write-Output "HUGO First Life documentation/order guard: PASS"
Write-Output "Task IDs: FL001-FL015 (15 ordered)"
Write-Output "Canonical backlog: tracked separately; First Life freeze unchanged"
