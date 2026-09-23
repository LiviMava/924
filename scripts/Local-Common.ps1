Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:WorkspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\', '/')
if (-not (Test-Path -LiteralPath (Join-Path $script:WorkspaceRoot 'java-chatbot') -PathType Container)) {
    throw 'Run these scripts from the RAG project containing java-chatbot.'
}

function Assert-WorkspacePath([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $prefix = $script:WorkspaceRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing a path outside this workspace.'
    }
    # A junction/symlink beneath the project could otherwise redirect writes elsewhere.
    $probe = $full
    while ($probe.Length -gt $script:WorkspaceRoot.Length) {
        if (Test-Path -LiteralPath $probe) {
            $item = Get-Item -LiteralPath $probe -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Refusing a workspace path containing a junction or symbolic link.'
            }
        }
        $probe = [IO.Path]::GetDirectoryName($probe)
    }
    return $full
}

function Get-LocalPath([string] $RelativePath) {
    return Assert-WorkspacePath (Join-Path $script:WorkspaceRoot $RelativePath)
}

function Initialize-LocalDirectories {
    foreach ($relative in @('.runtime', '.runtime/downloads', '.runtime/qdrant', '.runtime/qdrant/storage',
            '.runtime/qdrant/snapshots', '.runtime/qdrant/tmp', '.runtime/data', '.runtime/logs', '.runtime/pids', '.runtime/m2')) {
        New-Item -ItemType Directory -Path (Get-LocalPath $relative) -Force | Out-Null
    }
}

function Enter-LocalLock([string] $Name) {
    $path = Get-LocalPath ('.runtime/' + $Name + '.lock')
    try { return [IO.File]::Open($path, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch { throw 'Another local lifecycle command is running. Retry after it finishes.' }
}

function Import-LocalEnvironment {
    $path = Get-LocalPath '.local.env'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return }
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadAllLines($path)) {
        $lineNumber++
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            throw "Invalid .local.env syntax at line $lineNumber; only NAME=VALUE is supported."
        }
        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        # Literal assignment only: no evaluation, expansion, interpolation or shell execution.
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Get-LocalJava {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to a JDK 17 installation.' }
    $java = [IO.Path]::GetFullPath((Join-Path $env:JAVA_HOME 'bin/java.exe'))
    $release = Join-Path $env:JAVA_HOME 'release'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
            -not (Test-Path -LiteralPath $release -PathType Leaf) -or
            -not ((Get-Content -LiteralPath $release) -match '^JAVA_VERSION="17(?:\.|\")')) {
        throw 'JAVA_HOME must point to JDK 17; the java command on PATH is not used.'
    }
    return $java
}

function Get-OwnedProcess([string] $Name, [string] $Executable, [string] $Marker) {
    $recordPath = Get-LocalPath ('.runtime/pids/' + $Name + '.json')
    if (-not (Test-Path -LiteralPath $recordPath -PathType Leaf)) { return $null }
    $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    if ($record.workspace -ne $script:WorkspaceRoot -or $record.executable -ne $Executable -or $record.marker -ne $Marker) {
        throw "Refusing mismatched $Name PID metadata. No process was changed."
    }
    $process = Get-Process -Id ([int]$record.processId) -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        Remove-Item -LiteralPath $recordPath
        return $null
    }
    $details = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + [int]$record.processId)
    if ($null -eq $details -or [string]::IsNullOrEmpty($details.ExecutablePath) -or
            -not $details.ExecutablePath.Equals($Executable, [StringComparison]::OrdinalIgnoreCase) -or
            [string]::IsNullOrEmpty($details.CommandLine) -or
            $details.CommandLine.IndexOf($Marker, [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
            $process.StartTime.ToUniversalTime().Ticks.ToString() -ne $record.startTimeUtcTicks) {
        throw "Refusing to control an unverified $Name process (PID may have been reused)."
    }
    return $process
}

function Save-OwnedProcess([string] $Name, $Process, [string] $Executable, [string] $Marker) {
    $Process.Refresh()
    if ($Process.HasExited) { throw "$Name exited during startup. See .runtime/logs." }
    $recordPath = Get-LocalPath ('.runtime/pids/' + $Name + '.json')
    @{
        workspace = $script:WorkspaceRoot
        processId = $Process.Id
        startTimeUtcTicks = $Process.StartTime.ToUniversalTime().Ticks.ToString()
        executable = $Executable
        marker = $Marker
    } | ConvertTo-Json | Set-Content -LiteralPath $recordPath -Encoding UTF8
}

function Assert-LocalPortsAvailable([int[]] $Ports) {
    $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    foreach ($port in $Ports) {
        if ($listeners | Where-Object { $_.Port -eq $port }) {
            throw "Port $port is already in use by an unmanaged service; no process was stopped."
        }
    }
}

function Wait-LocalHealth([string] $Url, $Process, [int] $TimeoutSeconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()
        if ($Process.HasExited) { throw 'A service exited during startup. See .runtime/logs.' }
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw 'Startup health check timed out. The tracked process is left available for diagnosis; see .runtime/logs.'
}
