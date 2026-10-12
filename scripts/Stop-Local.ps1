[CmdletBinding()]
param([switch] $AppOnly, [switch] $NxMcpOnly)

. (Join-Path $PSScriptRoot 'Local-Common.ps1')
Initialize-LocalDirectories
$controlLock = Enter-LocalLock 'control'
try {
    # Read the executable from our record so changing JAVA_HOME does not orphan the app.
    $names = if ($AppOnly) { @('app') } elseif ($NxMcpOnly) { @('nx-mcp') } else { @('app', 'nx-mcp', 'qdrant') }
    foreach ($name in $names) {
        $recordPath = Get-LocalPath ('.runtime/pids/' + $name + '.json')
        if (-not (Test-Path -LiteralPath $recordPath -PathType Leaf)) { continue }
        $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
        if ($name -in @('app', 'nx-mcp')) {
            $executable = [string]$record.executable
            if ([IO.Path]::GetFileName($executable) -ne 'java.exe') { throw 'Invalid application executable in PID record.' }
            $marker = if ($name -eq 'app') { Get-LocalPath 'java-chatbot/target/java-chatbot-1.0.0.jar' } else { Get-LocalPath 'nx-mcp-server/target/nx-mcp-server-1.0.0.jar' }
        } else {
            $executable = Get-LocalPath '.runtime/qdrant/qdrant.exe'
            $marker = Get-LocalPath 'infra/qdrant-local.yaml'
        }
        $process = Get-OwnedProcess $name $executable $marker
        if ($null -ne $process) {
            Stop-Process -InputObject $process -ErrorAction Stop
            $process.WaitForExit(10000) | Out-Null
            $process.Refresh()
            if (-not $process.HasExited) { throw "$name has not exited; its PID record was retained." }
            Remove-Item -LiteralPath $recordPath
            Write-Host "Stopped project $name process."
        }
    }
    Write-Host 'Project services stopped; data and logs retained.'
} finally {
    $controlLock.Dispose()
}
