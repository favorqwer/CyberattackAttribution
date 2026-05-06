[CmdletBinding()]
param(
    [string]$ManifestPath = ".\test\theia-e5-official-2.cdm",
    [string]$Poi = "file://08000000-66cd-0000-0000-000000000000",
    [string]$BaselineCommit = "HEAD~1",
    [int]$PollIntervalMs = 200,
    [switch]$SkipPackage
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoRoot {
    if ($PSScriptRoot) {
        return (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
    }
    return (Get-Location).Path
}

function Ensure-Command {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $cmd) {
        throw "Required command not found: $Name"
    }
    return $cmd.Source
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    Write-Host ">> $FilePath $($Arguments -join ' ')"
    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code ${LASTEXITCODE}: $FilePath $($Arguments -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Content
    )

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Parse-Metrics {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Stdout
    )

    $metrics = @{}
    foreach ($line in ($Stdout -split "`r?`n")) {
        if ($line -match '^([A-Z_]+)=(.+)$') {
            $metrics[$matches[1]] = $matches[2].Trim()
        }
    }
    return $metrics
}

function Run-Probe {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Mode,
        [Parameter(Mandatory = $true)]
        [string]$ClassPath,
        [Parameter(Mandatory = $true)]
        [string]$ProbeClass,
        [Parameter(Mandatory = $true)]
        [string]$ManifestPath,
        [Parameter(Mandatory = $true)]
        [string]$Poi,
        [Parameter(Mandatory = $true)]
        [string]$OutputDir,
        [Parameter(Mandatory = $true)]
        [int]$PollIntervalMs
    )

    $stdoutPath = Join-Path $OutputDir "$Mode.stdout.txt"
    $stderrPath = Join-Path $OutputDir "$Mode.stderr.txt"

    $args = @("-cp", $ClassPath, $ProbeClass, $Mode, $ManifestPath, $Poi)
    $proc = Start-Process -FilePath "java" -ArgumentList $args -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -WindowStyle Hidden

    $peakPrivateBytes = 0L
    while (-not $proc.HasExited) {
        try {
            $p = Get-Process -Id $proc.Id -ErrorAction Stop
            if ($p.PrivateMemorySize64 -gt $peakPrivateBytes) {
                $peakPrivateBytes = $p.PrivateMemorySize64
            }
        } catch {
        }
        Start-Sleep -Milliseconds $PollIntervalMs
        $proc.Refresh()
    }
    $proc.WaitForExit()

    $stdout = Get-Content $stdoutPath -Raw
    $stderr = ""
    if (Test-Path $stderrPath) {
        $stderr = Get-Content $stderrPath -Raw
    }

    $metrics = Parse-Metrics -Stdout $stdout
    return [pscustomobject]@{
        Mode = $Mode
        ExitCode = $proc.ExitCode
        PeakPrivateMB = [math]::Round($peakPrivateBytes / 1MB, 2)
        Stdout = $stdout
        Stderr = $stderr
        Metrics = $metrics
    }
}

$repoRoot = Resolve-RepoRoot
$git = Ensure-Command -Name "git"
$javac = Ensure-Command -Name "javac"
$mvn = Ensure-Command -Name "mvn"

$resolvedManifest = (Resolve-Path (Join-Path $repoRoot $ManifestPath)).Path
$jarPath = Join-Path $repoRoot "target\reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar"

if (-not $SkipPackage) {
    Invoke-Checked -FilePath $mvn -Arguments @("-q", "-DskipTests", "package") -WorkingDirectory $repoRoot
}

if (-not (Test-Path $jarPath)) {
    throw "Current fat jar not found: $jarPath"
}

$runId = Get-Date -Format "yyyyMMdd_HHmmss"
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) "depimpact_preslice_compare_$runId"
New-Item -ItemType Directory -Path $tempRoot | Out-Null
$legacySrcDir = Join-Path $tempRoot "src\logparsers\cdm"
New-Item -ItemType Directory -Path $legacySrcDir -Force | Out-Null

$legacySourcePath = Join-Path $legacySrcDir "LegacyCdmGraphBuilder.java"
$probeSourcePath = Join-Path $tempRoot "LegacyVsPresliceProbe.java"
$resultJsonPath = Join-Path $tempRoot "compare_result.json"

$legacySource = (& $git -C $repoRoot show "$BaselineCommit`:src/main/java/logparsers/cdm/CdmGraphBuilder.java" | Out-String)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($legacySource)) {
    throw "Failed to load legacy CdmGraphBuilder from commit $BaselineCommit"
}
$legacySource = $legacySource -replace '\bCdmGraphBuilder\b', 'LegacyCdmGraphBuilder'
Write-Utf8NoBom -Path $legacySourcePath -Content $legacySource

$probeSource = @"
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import logparsers.cdm.CdmGraphBuilder;
import logparsers.cdm.LegacyCdmGraphBuilder;
import org.jgrapht.graph.DirectedPseudograph;
import pagerank.algorithm.BackTrack;
import pagerank.entity.EntityNode;
import pagerank.entity.EventEdge;

public class LegacyVsPresliceProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: <mode> <manifest> <poi>");
        }
        String mode = args[0];
        Path manifest = Path.of(args[1]);
        String poi = args[2];

        long buildStart = System.nanoTime();
        DirectedPseudograph<EntityNode, EventEdge> graph;
        if ("legacy".equalsIgnoreCase(mode)) {
            graph = LegacyCdmGraphBuilder.build(manifest);
        } else if ("preslice".equalsIgnoreCase(mode)) {
            graph = CdmGraphBuilder.build(manifest, List.of(poi));
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
        long buildEnd = System.nanoTime();

        BackTrack backTrack = new BackTrack(graph);
        long sliceStart = System.nanoTime();
        DirectedPseudograph<EntityNode, EventEdge> slice = backTrack.backTrackPOIEvent(poi);
        long sliceEnd = System.nanoTime();

        System.out.println("MODE=" + mode);
        System.out.println("BUILD_SECONDS=" + String.format(Locale.ROOT, "%.3f", (buildEnd - buildStart) / 1_000_000_000.0));
        System.out.println("BACKTRACK_SECONDS=" + String.format(Locale.ROOT, "%.3f", (sliceEnd - sliceStart) / 1_000_000_000.0));
        System.out.println("GRAPH_VERTICES=" + graph.vertexSet().size());
        System.out.println("GRAPH_EDGES=" + graph.edgeSet().size());
        System.out.println("SLICE_VERTICES=" + slice.vertexSet().size());
        System.out.println("SLICE_EDGES=" + slice.edgeSet().size());
    }
}
"@
Write-Utf8NoBom -Path $probeSourcePath -Content $probeSource

Invoke-Checked -FilePath $javac -Arguments @(
    "-cp", $jarPath,
    "-d", $tempRoot,
    $legacySourcePath,
    $probeSourcePath
) -WorkingDirectory $repoRoot

$classPath = "$tempRoot;$jarPath"
$legacy = Run-Probe -Mode "legacy" -ClassPath $classPath -ProbeClass "LegacyVsPresliceProbe" `
    -ManifestPath $resolvedManifest -Poi $Poi -OutputDir $tempRoot -PollIntervalMs $PollIntervalMs
$preslice = Run-Probe -Mode "preslice" -ClassPath $classPath -ProbeClass "LegacyVsPresliceProbe" `
    -ManifestPath $resolvedManifest -Poi $Poi -OutputDir $tempRoot -PollIntervalMs $PollIntervalMs

$result = [pscustomobject]@{
    manifest = $resolvedManifest
    poi = $Poi
    baselineCommit = $BaselineCommit
    tempDir = $tempRoot
    legacy = [pscustomobject]@{
        peakPrivateMB = $legacy.PeakPrivateMB
        buildSeconds = [double]$legacy.Metrics["BUILD_SECONDS"]
        backtrackSeconds = [double]$legacy.Metrics["BACKTRACK_SECONDS"]
        graphVertices = [int]$legacy.Metrics["GRAPH_VERTICES"]
        graphEdges = [int]$legacy.Metrics["GRAPH_EDGES"]
        sliceVertices = [int]$legacy.Metrics["SLICE_VERTICES"]
        sliceEdges = [int]$legacy.Metrics["SLICE_EDGES"]
    }
    preslice = [pscustomobject]@{
        peakPrivateMB = $preslice.PeakPrivateMB
        buildSeconds = [double]$preslice.Metrics["BUILD_SECONDS"]
        backtrackSeconds = [double]$preslice.Metrics["BACKTRACK_SECONDS"]
        graphVertices = [int]$preslice.Metrics["GRAPH_VERTICES"]
        graphEdges = [int]$preslice.Metrics["GRAPH_EDGES"]
        sliceVertices = [int]$preslice.Metrics["SLICE_VERTICES"]
        sliceEdges = [int]$preslice.Metrics["SLICE_EDGES"]
    }
}

$json = $result | ConvertTo-Json -Depth 5
Write-Utf8NoBom -Path $resultJsonPath -Content $json

Write-Host ""
Write-Host "=== CDM Preslice Comparison ==="
Write-Host "Manifest: $resolvedManifest"
Write-Host "POI: $Poi"
Write-Host "Baseline commit: $BaselineCommit"
Write-Host "Artifacts: $tempRoot"
Write-Host ""
Write-Host ("{0,-12} {1,12} {2,12} {3,12} {4,12} {5,12} {6,12}" -f "Mode", "PeakMB", "BuildS", "GraphV", "GraphE", "SliceV", "SliceE")
Write-Host ("{0,-12} {1,12:N2} {2,12:N3} {3,12} {4,12} {5,12} {6,12}" -f "legacy",
    $result.legacy.peakPrivateMB, $result.legacy.buildSeconds, $result.legacy.graphVertices,
    $result.legacy.graphEdges, $result.legacy.sliceVertices, $result.legacy.sliceEdges)
Write-Host ("{0,-12} {1,12:N2} {2,12:N3} {3,12} {4,12} {5,12} {6,12}" -f "preslice",
    $result.preslice.peakPrivateMB, $result.preslice.buildSeconds, $result.preslice.graphVertices,
    $result.preslice.graphEdges, $result.preslice.sliceVertices, $result.preslice.sliceEdges)
Write-Host ""
Write-Host ("Peak memory delta (preslice - legacy): {0:N2} MB" -f ($result.preslice.peakPrivateMB - $result.legacy.peakPrivateMB))
Write-Host ("Build time delta (preslice - legacy): {0:N3} s" -f ($result.preslice.buildSeconds - $result.legacy.buildSeconds))
Write-Host ("Slice edge delta (preslice - legacy): {0}" -f ($result.preslice.sliceEdges - $result.legacy.sliceEdges))
Write-Host "JSON summary: $resultJsonPath"
