param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-True {
    param([bool]$Condition, [string]$Message)

    if (-not $Condition) {
        throw "T032 baseline evidence guard failed: $Message"
    }
}

function Get-GitOutput {
    param([string[]]$GitArguments)

    $output = & git @GitArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git $($GitArguments -join ' ') failed: $($output -join [Environment]::NewLine)"
    }
    return ($output -join "`n").Trim()
}

$manifestPath = Join-Path $RepositoryRoot "architecture/evidence/t001-t032-baseline.json"
Assert-True (Test-Path -LiteralPath $manifestPath -PathType Leaf) "manifest is missing"
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json

Assert-True ($manifest.schemaVersion -eq "1.0") "unsupported schema version"
Assert-True ($manifest.checkpointId -eq "T001-T032") "unexpected checkpoint ID"
Assert-True ($manifest.lockedByTask -eq "H002") "unexpected locking task"
Assert-True ($manifest.taskRange.first -eq "T001") "task range must begin at T001"
Assert-True ($manifest.taskRange.last -eq "T032") "task range must end at T032"
Assert-True ($manifest.taskRange.count -eq 32) "task range count must be 32"

Push-Location $RepositoryRoot
try {
    $checkpointCommit = [string]$manifest.repositoryCheckpoint.commit
    $checkpointTree = [string]$manifest.repositoryCheckpoint.tree
    $architectureCommit = [string]$manifest.architectureBaseline.commit
    $architectureTree = [string]$manifest.architectureBaseline.tree

    Assert-True ((Get-GitOutput @("cat-file", "-t", $checkpointCommit)) -eq "commit") "T032 commit is unavailable"
    Assert-True ((Get-GitOutput @("rev-parse", "$checkpointCommit`^{tree}")) -eq $checkpointTree) "T032 tree mismatch"
    Assert-True ((Get-GitOutput @("cat-file", "-t", $architectureCommit)) -eq "commit") "H001 commit is unavailable"
    Assert-True ((Get-GitOutput @("rev-parse", "$architectureCommit`^{tree}")) -eq $architectureTree) "H001 tree mismatch"
    Assert-True ((Get-GitOutput @("rev-parse", "$architectureCommit`^")) -eq $checkpointCommit) "H001 must directly descend from T032"

    & git merge-base --is-ancestor $architectureCommit HEAD
    Assert-True ($LASTEXITCODE -eq 0) "HEAD must descend from the accepted H001 baseline"

    $repositoryFiles = @((Get-GitOutput @("ls-tree", "-r", "--name-only", $checkpointCommit)) -split "`n")
    Assert-True ($repositoryFiles.Count -eq [int]$manifest.coverage.repositoryFileCount) "repository file count mismatch"

    $indexedPaths = @($manifest.evidence | ForEach-Object { [string]$_.path })
    Assert-True ($indexedPaths.Count -eq (@($indexedPaths | Sort-Object -Unique)).Count) "duplicate evidence paths"

    $actualEvidencePaths = [System.Collections.Generic.List[string]]::new()
    foreach ($root in $manifest.coverage.evidenceRoots) {
        $rootPath = [string]$root.path
        $rootFiles = @((Get-GitOutput @("ls-tree", "-r", "--name-only", $checkpointCommit, "--", $rootPath)) -split "`n")
        Assert-True ($rootFiles.Count -eq [int]$root.fileCount) "evidence count mismatch for $rootPath"
        foreach ($rootFile in $rootFiles) {
            $actualEvidencePaths.Add($rootFile)
        }
    }

    $pathDelta = @(Compare-Object @($actualEvidencePaths | Sort-Object) @($indexedPaths | Sort-Object))
    Assert-True ($pathDelta.Count -eq 0) "evidence index does not exactly cover the declared roots"

    foreach ($entry in $manifest.evidence) {
        $path = [string]$entry.path
        $expectedBlob = [string]$entry.blob
        $actualBlob = Get-GitOutput @("rev-parse", "$checkpointCommit`:$path")
        Assert-True ($actualBlob -eq $expectedBlob) "blob mismatch for $path"
        Assert-True ((Get-GitOutput @("cat-file", "-t", $actualBlob)) -eq "blob") "non-blob evidence object for $path"
    }

    $acceptancePath = [string]$manifest.architectureBaseline.acceptanceRecordPath
    Assert-True ((Get-GitOutput @("cat-file", "-t", "$architectureCommit`:$acceptancePath")) -eq "blob") "H001 acceptance record is unavailable"
}
finally {
    Pop-Location
}

Assert-True (-not [bool]$manifest.claims.runtimeBehaviorModified) "manifest must not claim runtime modification"
Assert-True (-not [bool]$manifest.claims.historyResigned) "manifest must not re-sign history"
Assert-True (-not [bool]$manifest.claims.hardwareReverified) "H002 must not claim new hardware verification"

Write-Output "T032 baseline evidence guard: PASS"
Write-Output "Checkpoint: $($manifest.repositoryCheckpoint.commit)"
Write-Output "Repository tree: $($manifest.repositoryCheckpoint.tree)"
Write-Output "Indexed evidence blobs: $(@($manifest.evidence).Count)"
Write-Output "Coverage: 16 documents, 14 fixtures, 139 repository files through the locked tree"
