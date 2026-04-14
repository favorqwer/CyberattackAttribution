[CmdletBinding()]
param(
    [string]$DatasetRoot = "",
    [string[]]$Providers,
    [ValidateSet("json.gz", "json")]
    [string]$OutputFormat = "json.gz",
    [string]$OutputRoot,
    [switch]$ExpandExistingJsonGz,
    [switch]$Force,
    [switch]$KeepTemp,
    [int]$MaxFiles = 0,
    [ValidateRange(1, 64)]
    [int]$Parallelism = 1,
    [switch]$WorkerMode,
    [ValidateSet("bin", "json")]
    [string]$WorkerKind = "",
    [string]$WorkerSourcePath = "",
    [string]$WorkerTargetPath = "",
    [int]$WorkerTaskIndex = 0,
    [int]$WorkerTaskCount = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptPath = $MyInvocation.MyCommand.Path
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($DatasetRoot)) {
    $DatasetRoot = Join-Path $scriptRoot "..\Transparent_Computing_Engagement_5_Dataset"
}

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CommandName,
        [string]$FallbackPath
    )

    $cmd = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    if ($FallbackPath -and (Test-Path -LiteralPath $FallbackPath)) {
        return $FallbackPath
    }

    throw "Unable to locate tool '$CommandName'."
}

function Ensure-Directory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path | Out-Null
    }
}

function Get-JsonSiblingName {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    if ($Name -match '\.bin(\.\d+)?\.gz$') {
        return ($Name -replace '\.bin(\.\d+)?\.gz$', '.json$1.gz')
    }

    throw "Unsupported bin.gz filename: $Name"
}

function Get-JsonNameFromJsonGz {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    if ($Name -match '\.json(\.\d+)?\.gz$') {
        return ($Name -replace '\.gz$', '')
    }

    throw "Unsupported json.gz filename: $Name"
}

function Get-RelativePathCompat {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BasePath,
        [Parameter(Mandatory = $true)]
        [string]$TargetPath
    )

    $baseUri = New-Object System.Uri(((Resolve-Path -LiteralPath $BasePath).Path.TrimEnd('\') + '\'))
    $targetUri = New-Object System.Uri((Resolve-Path -LiteralPath $TargetPath).Path)
    $relativeUri = $baseUri.MakeRelativeUri($targetUri)
    return [System.Uri]::UnescapeDataString($relativeUri.ToString().Replace('/', '\'))
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    Write-Host ">> $FilePath $($Arguments -join ' ')"
    $previousErrorPreference = $ErrorActionPreference
    $script:ErrorActionPreference = "Continue"
    try {
        & $FilePath @Arguments 2>&1 | ForEach-Object { Write-Host $_ }
    } finally {
        $script:ErrorActionPreference = $previousErrorPreference
    }

    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $FilePath"
    }
}

function Invoke-ExternalInDir {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory
    )

    Push-Location $WorkingDirectory
    try {
        Invoke-External -FilePath $FilePath -Arguments $Arguments
    } finally {
        Pop-Location
    }
}

function Expand-GzipFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SevenZipPath,
        [Parameter(Mandatory = $true)]
        [string]$ArchivePath,
        [Parameter(Mandatory = $true)]
        [string]$DestinationDirectory
    )

    Ensure-Directory -Path $DestinationDirectory
    Invoke-External -FilePath $SevenZipPath -Arguments @(
        "x",
        "-y",
        "-bso0",
        "-bsp1",
        "-bse1",
        "-o$DestinationDirectory",
        $ArchivePath
    )

    $extractedFiles = @(Get-ChildItem -LiteralPath $DestinationDirectory -File)
    if ($extractedFiles.Count -ne 1) {
        throw "Expected exactly one extracted file from $ArchivePath, found $($extractedFiles.Count)."
    }

    return $extractedFiles[0].FullName
}

function Compress-GzipFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SevenZipPath,
        [Parameter(Mandatory = $true)]
        [string]$InputFile,
        [Parameter(Mandatory = $true)]
        [string]$OutputArchive
    )

    $parent = Split-Path -Parent $OutputArchive
    Ensure-Directory -Path $parent
    if (Test-Path -LiteralPath $OutputArchive) {
        Remove-Item -LiteralPath $OutputArchive -Force
    }

    Invoke-External -FilePath $SevenZipPath -Arguments @(
        "a",
        "-tgzip",
        "-mx=5",
        "-y",
        $OutputArchive,
        $InputFile
    )
}

function Get-OutputJsonPath {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$SourceFile,
        [Parameter(Mandatory = $true)]
        [string]$DataRoot,
        [string]$OutputRoot
    )

    $relativeDirectory = Get-RelativePathCompat -BasePath $DataRoot -TargetPath $SourceFile.DirectoryName
    $targetDirectory = if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
        $SourceFile.DirectoryName
    } else {
        Join-Path $OutputRoot $relativeDirectory
    }

    Ensure-Directory -Path $targetDirectory

    if ($SourceFile.Name -match '\.bin(\.\d+)?\.gz$') {
        $baseName = Get-JsonSiblingName -Name $SourceFile.Name
    } elseif ($SourceFile.Name -match '\.json(\.\d+)?\.gz$') {
        $baseName = $SourceFile.Name
    } else {
        throw "Unsupported source file: $($SourceFile.FullName)"
    }

    if ($OutputFormat -eq "json.gz") {
        return Join-Path $targetDirectory $baseName
    }

    return Join-Path $targetDirectory (Get-JsonNameFromJsonGz -Name $baseName)
}

function Invoke-ConversionTask {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("bin", "json")]
        [string]$Kind,
        [Parameter(Mandatory = $true)]
        [string]$SourcePath,
        [Parameter(Mandatory = $true)]
        [string]$TargetPath,
        [int]$TaskIndex = 0,
        [int]$TaskCount = 0
    )

    $sourceFile = Get-Item -LiteralPath $SourcePath
    $taskTempDir = Join-Path $TempRoot ([System.IO.Path]::GetFileNameWithoutExtension($sourceFile.Name) + "_" + [guid]::NewGuid().ToString("N"))
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $statusMessage = ""

    Write-Host ""
    if ($TaskIndex -gt 0 -and $TaskCount -gt 0) {
        Write-Host ("[{0}/{1}] {2}" -f $TaskIndex, $TaskCount, $sourceFile.FullName)
    } else {
        Write-Host $sourceFile.FullName
    }

    Ensure-Directory -Path $taskTempDir

    try {
        if ($Kind -eq "json") {
            $jsonPath = Expand-GzipFile -SevenZipPath $SevenZipPath -ArchivePath $sourceFile.FullName -DestinationDirectory $taskTempDir
            Ensure-Directory -Path (Split-Path -Parent $TargetPath)
            Move-Item -LiteralPath $jsonPath -Destination $TargetPath -Force
            $statusMessage = "Expanded JSON -> $TargetPath"
            Write-Host $statusMessage
        } else {
            $binPath = Expand-GzipFile -SevenZipPath $SevenZipPath -ArchivePath $sourceFile.FullName -DestinationDirectory $taskTempDir
            $stageJsonDir = if ($OutputFormat -eq "json") {
                Split-Path -Parent $TargetPath
            } else {
                $taskTempDir
            }

            Ensure-Directory -Path $stageJsonDir

            Invoke-ExternalInDir -FilePath $JavaPath -WorkingDirectory $ConsumerDir -Arguments @(
                "-cp",
                "target\kafkaclients-1.0-SNAPSHOT-jar-with-dependencies.jar",
                "com.bbn.tc.services.kafka.FileConsumer",
                ("file:///" + ($binPath -replace "\\", "/")),
                "-np",
                "-psf",
                "..\ta3-serialization-schema\avro\TCCDMDatum.avsc",
                "-csf",
                "..\ta3-serialization-schema\avro\TCCDMDatum.avsc",
                "-rg",
                "-call",
                "-co",
                "earliest",
                "-cdm",
                "-c",
                "-roll",
                "2147483647",
                "-wj",
                "-d",
                "10000000",
                "-odir",
                $stageJsonDir,
                "-nometrics"
            )

            $stageJsonFiles = @(Get-ChildItem -LiteralPath $stageJsonDir -File | Where-Object {
                $_.Name -like ((Split-Path -Leaf $binPath) + ".json*")
            } | Sort-Object Name)

            if ($stageJsonFiles.Count -eq 0) {
                throw "Expected JSON output not found for $binPath"
            }

            if ($stageJsonFiles.Count -gt 1) {
                throw "Unexpected rollover output detected for $binPath. Adjust the roll size and retry."
            }

            $stageJsonFile = $stageJsonFiles[0].FullName

            if ($OutputFormat -eq "json.gz") {
                Compress-GzipFile -SevenZipPath $SevenZipPath -InputFile $stageJsonFile -OutputArchive $TargetPath
                $statusMessage = "Converted bin.gz -> json.gz: $TargetPath"
            } else {
                if ($stageJsonFile -ne $TargetPath) {
                    Move-Item -LiteralPath $stageJsonFile -Destination $TargetPath -Force
                }
                $statusMessage = "Converted bin.gz -> json: $TargetPath"
            }

            Write-Host $statusMessage
        }
    } finally {
        $stopwatch.Stop()
        if (-not $KeepTemp -and (Test-Path -LiteralPath $taskTempDir)) {
            Remove-Item -LiteralPath $taskTempDir -Recurse -Force
        }
    }

    [pscustomobject]@{
        Kind = $Kind
        Source = $sourceFile.FullName
        Target = $TargetPath
        DurationSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 2)
        Status = $statusMessage
    }
}

function Start-ConversionWorker {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Task,
        [Parameter(Mandatory = $true)]
        [int]$TaskIndex,
        [Parameter(Mandatory = $true)]
        [int]$TaskCount
    )

    $workerArgs = @{
        DatasetRoot = $DatasetRoot
        OutputFormat = $OutputFormat
        Parallelism = 1
        WorkerMode = $true
        WorkerKind = [string]$Task.Kind
        WorkerSourcePath = [string]$Task.Source.FullName
        WorkerTargetPath = [string]$Task.Target
        WorkerTaskIndex = $TaskIndex
        WorkerTaskCount = $TaskCount
    }

    if ($KeepTemp) {
        $workerArgs.KeepTemp = $true
    }

    $job = Start-Job -Name ("tc-convert-{0}" -f $TaskIndex) -ScriptBlock {
        param($ScriptPath, $ScriptArgs)
        & $ScriptPath @ScriptArgs
    } -ArgumentList $scriptPath, $workerArgs

    $job | Add-Member -NotePropertyName TaskIndex -NotePropertyValue $TaskIndex
    $job | Add-Member -NotePropertyName TaskSource -NotePropertyValue $Task.Source.FullName
    $job | Add-Member -NotePropertyName TaskTarget -NotePropertyValue ([string]$Task.Target)
    return $job
}

function Receive-ConversionWorker {
    param(
        [Parameter(Mandatory = $true)]
        [System.Management.Automation.Job]$Job
    )

    $jobOutput = @(Receive-Job -Job $Job)
    $summary = $jobOutput | Where-Object {
        $_ -and $_.PSObject.Properties["Status"] -and $_.PSObject.Properties["DurationSeconds"]
    } | Select-Object -Last 1

    if ($Job.State -ne "Completed") {
        $reason = $Job.ChildJobs[0].JobStateInfo.Reason
        if ($reason) {
            throw "Worker failed for $($Job.TaskSource): $reason"
        }

        throw "Worker failed for $($Job.TaskSource)."
    }

    if ($summary) {
        Write-Host ("Finished [{0}] in {1}s -> {2}" -f $Job.TaskIndex, $summary.DurationSeconds, $summary.Target)
    } else {
        Write-Host ("Finished [{0}] -> {1}" -f $Job.TaskIndex, $Job.TaskTarget)
    }
}

function Invoke-TasksInParallel {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$TaskList,
        [Parameter(Mandatory = $true)]
        [int]$MaxParallelWorkers
    )

    $runningJobs = @()
    $nextIndex = 0
    $completed = 0

    Write-Host ("Running with up to {0} parallel worker(s)." -f $MaxParallelWorkers)

    try {
        while ($completed -lt $TaskList.Count) {
            while ($nextIndex -lt $TaskList.Count -and $runningJobs.Count -lt $MaxParallelWorkers) {
                $task = $TaskList[$nextIndex]
                $taskNumber = $nextIndex + 1
                Write-Host ("Queued [{0}/{1}] {2}" -f $taskNumber, $TaskList.Count, $task.Source.FullName)
                $runningJobs += Start-ConversionWorker -Task $task -TaskIndex $taskNumber -TaskCount $TaskList.Count
                $nextIndex++
            }

            $finishedJob = Wait-Job -Job $runningJobs -Any
            Receive-ConversionWorker -Job $finishedJob
            Remove-Job -Job $finishedJob -Force | Out-Null
            $runningJobs = @($runningJobs | Where-Object { $_.Id -ne $finishedJob.Id })
            $completed++
            Write-Host ("Progress: {0}/{1} completed." -f $completed, $TaskList.Count)
        }
    } catch {
        foreach ($job in $runningJobs) {
            Stop-Job -Job $job -ErrorAction SilentlyContinue | Out-Null
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue | Out-Null
        }

        throw
    }
}

$DatasetRoot = (Resolve-Path -LiteralPath $DatasetRoot).Path
$DataRoot = Join-Path $DatasetRoot "Data"
$SchemaPath = Join-Path $DatasetRoot "Schema\TCCDMDatum.avsc"
$ConsumerDir = Join-Path $DatasetRoot "Tools\ta3-java-consumer\tc-bbn-kafka"
$ConsumerJar = Join-Path $ConsumerDir "target\kafkaclients-1.0-SNAPSHOT-jar-with-dependencies.jar"
$TempRoot = Join-Path $DatasetRoot "_tc_convert_tmp"

if (-not (Test-Path -LiteralPath $DataRoot)) {
    throw "Data directory not found: $DataRoot"
}

if (-not (Test-Path -LiteralPath $SchemaPath)) {
    throw "Schema file not found: $SchemaPath"
}

if (-not (Test-Path -LiteralPath $ConsumerJar)) {
    throw "Consumer jar not found: $ConsumerJar"
}

$SevenZipPath = Resolve-ToolPath -CommandName "7z" -FallbackPath "C:\Program Files\7-Zip\7z.exe"
$JavaPath = Resolve-ToolPath -CommandName "java" -FallbackPath $null

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    if ($OutputFormat -eq "json") {
        $OutputRoot = Join-Path $DatasetRoot "Readable_Json"
    } else {
        $OutputRoot = ""
    }
} else {
    $resolvedOutputRoot = Resolve-Path -LiteralPath $OutputRoot -ErrorAction SilentlyContinue
    if ($resolvedOutputRoot) {
        $OutputRoot = $resolvedOutputRoot.Path
    }
}

if ($OutputRoot) {
    Ensure-Directory -Path $OutputRoot
}

Ensure-Directory -Path $TempRoot

if ($WorkerMode) {
    if ([string]::IsNullOrWhiteSpace($WorkerKind) -or
        [string]::IsNullOrWhiteSpace($WorkerSourcePath) -or
        [string]::IsNullOrWhiteSpace($WorkerTargetPath)) {
        throw "WorkerMode requires WorkerKind, WorkerSourcePath, and WorkerTargetPath."
    }

    Invoke-ConversionTask -Kind $WorkerKind -SourcePath $WorkerSourcePath -TargetPath $WorkerTargetPath -TaskIndex $WorkerTaskIndex -TaskCount $WorkerTaskCount | Out-Null
    exit 0
}

$providerPaths = if ($Providers -and $Providers.Count -gt 0) {
    foreach ($provider in $Providers) {
        $providerPath = Join-Path $DataRoot $provider
        if (-not (Test-Path -LiteralPath $providerPath)) {
            throw "Provider directory not found: $providerPath"
        }
        (Resolve-Path -LiteralPath $providerPath).Path
    }
} else {
    (Resolve-Path -LiteralPath $DataRoot).Path
}

$binCandidates = @()
$jsonCandidates = @()

foreach ($providerPath in $providerPaths) {
    $binCandidates += Get-ChildItem -LiteralPath $providerPath -Recurse -File | Where-Object {
        $_.Name -match '\.bin(\.\d+)?\.gz$'
    }

    if ($OutputFormat -eq "json" -and $ExpandExistingJsonGz) {
        $jsonCandidates += Get-ChildItem -LiteralPath $providerPath -Recurse -File | Where-Object {
            $_.Name -match '\.json(\.\d+)?\.gz$'
        }
    }
}

$tasks = New-Object System.Collections.Generic.List[object]

foreach ($file in $binCandidates | Sort-Object FullName) {
    $targetPath = Get-OutputJsonPath -SourceFile $file -DataRoot $DataRoot -OutputRoot $OutputRoot
    if ((-not $Force) -and (Test-Path -LiteralPath $targetPath)) {
        continue
    }

    $tasks.Add([pscustomobject]@{
        Kind = "bin"
        Source = $file
        Target = $targetPath
    })
}

foreach ($file in $jsonCandidates | Sort-Object FullName) {
    $targetPath = Get-OutputJsonPath -SourceFile $file -DataRoot $DataRoot -OutputRoot $OutputRoot
    if ((-not $Force) -and (Test-Path -LiteralPath $targetPath)) {
        continue
    }

    $tasks.Add([pscustomobject]@{
        Kind = "json"
        Source = $file
        Target = $targetPath
    })
}

if ($MaxFiles -gt 0) {
    $tasks = @($tasks | Select-Object -First $MaxFiles)
} else {
    $tasks = $tasks.ToArray()
}

Write-Host "DatasetRoot: $DatasetRoot"
Write-Host "OutputFormat: $OutputFormat"
Write-Host "OutputRoot: $(if ($OutputRoot) { $OutputRoot } else { '<in-place>' })"
Write-Host "Parallelism: $Parallelism"
Write-Host "Tasks: $($tasks.Count)"

if ($tasks.Count -eq 0) {
    Write-Host "Nothing to do."
    exit 0
}

$completed = 0
if ($Parallelism -le 1 -or $tasks.Count -le 1) {
    foreach ($task in $tasks) {
        $completed++
        Invoke-ConversionTask -Kind $task.Kind -SourcePath $task.Source.FullName -TargetPath $task.Target -TaskIndex $completed -TaskCount $tasks.Count | Out-Null
    }
} else {
    Invoke-TasksInParallel -TaskList $tasks -MaxParallelWorkers ([Math]::Min($Parallelism, $tasks.Count))
    $completed = $tasks.Count
}

Write-Host ""
Write-Host "Completed $completed task(s)."
