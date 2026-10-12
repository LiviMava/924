[CmdletBinding()]
param([switch] $SkipBuild, [switch] $Mock)
. (Join-Path $PSScriptRoot 'Local-Common.ps1')
Initialize-LocalDirectories
$nxLock = Enter-LocalLock 'control'
try {
    Import-LocalEnvironment
    # A distinct project service token; never reuse the DeepSeek provider key.
    if ([string]::IsNullOrWhiteSpace($env:NX_MCP_API_KEY)) {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $env:NX_MCP_API_KEY = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
        $localEnv = Get-LocalPath '.local.env'
        if (-not (Test-Path -LiteralPath $localEnv)) { Copy-Item -LiteralPath (Get-LocalPath '.env.example') -Destination $localEnv }
        $text = [IO.File]::ReadAllText($localEnv)
        if ($text -match '(?m)^NX_MCP_API_KEY=') { $text = [regex]::Replace($text, '(?m)^NX_MCP_API_KEY=.*$', 'NX_MCP_API_KEY=' + $env:NX_MCP_API_KEY) }
        else { $text += [Environment]::NewLine + 'NX_MCP_API_KEY=' + $env:NX_MCP_API_KEY + [Environment]::NewLine }
        [IO.File]::WriteAllText($localEnv, $text, (New-Object Text.UTF8Encoding $false))
    }
    $java = Get-LocalJava
    $jar = Get-LocalPath 'nx-mcp-server/target/nx-mcp-server-1.0.0.jar'
    $nxProcess = Get-OwnedProcess 'nx-mcp' $java $jar
    if ($null -ne $nxProcess) { throw 'NX MCP is already running. Stop it with Stop-Local.ps1 -NxMcpOnly before changing its backend or rebuilding.' }
    Assert-LocalPortsAvailable @(8765)
    if (-not $SkipBuild) {
        $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($null -eq $maven) { throw 'Maven is required on PATH, or use -SkipBuild with a tested jar.' }
        & $maven.Source '-B' '-gs' (Get-LocalPath 'infra/maven-settings.xml') '-s' (Get-LocalPath 'infra/maven-settings.xml') `
            '-f' (Get-LocalPath 'nx-mcp-server/pom.xml') ('-Dmaven.repo.local=' + (Get-LocalPath '.runtime/m2')) 'package'
        if ($LASTEXITCODE -ne 0) { throw 'NX MCP build failed.' }
    }
    if (-not (Test-Path -LiteralPath $jar)) { throw 'NX MCP jar is missing.' }
    $env:NX_BRIDGE_DIR = Get-LocalPath '.runtime/nx-bridge'
    $env:NX_MCP_PORT = '8765'
    $env:NX_BACKEND = if ($Mock) { 'mock' } else { 'bridge' }
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:SERVER_PORT = '8765'
    # Avoid inheriting the RAG module's profile or datasource overrides.
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $nxProcess = Start-Process -FilePath $java -ArgumentList @('-jar', ('"' + $jar + '"')) `
        -WorkingDirectory $script:WorkspaceRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Get-LocalPath '.runtime/logs/nx-mcp.stdout.log') `
        -RedirectStandardError (Get-LocalPath '.runtime/logs/nx-mcp.stderr.log')
    Save-OwnedProcess 'nx-mcp' $nxProcess $java $jar
    Wait-LocalHealth 'http://127.0.0.1:8765/actuator/health' $nxProcess 60
    Write-Host ('NX MCP ready: http://127.0.0.1:8765/mcp; backend=' + $env:NX_BACKEND)
    if ($Mock) { Write-Host 'MOCK is simulation only and never changes NX.' }
} finally { $nxLock.Dispose() }
