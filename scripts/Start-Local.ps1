[CmdletBinding()]
param([switch] $SkipBuild, [switch] $QdrantOnly)

. (Join-Path $PSScriptRoot 'Local-Common.ps1')
Initialize-LocalDirectories
$controlLock = Enter-LocalLock 'control'
try {
    Import-LocalEnvironment
    & (Join-Path $PSScriptRoot 'Install-Local.ps1')
    $qdrant = Get-LocalPath '.runtime/qdrant/qdrant.exe'
    $config = Get-LocalPath 'infra/qdrant-local.yaml'
    $qdrantProcess = Get-OwnedProcess 'qdrant' $qdrant $config
    if ($null -eq $qdrantProcess) {
        Assert-LocalPortsAvailable @(6333, 6334)
        # Environment overrides take precedence over YAML; prevent inherited overrides.
        $qdrantEnvironment = @{}
        foreach ($entry in @(Get-ChildItem Env: | Where-Object { $_.Name.StartsWith('QDRANT__') })) {
            $qdrantEnvironment[$entry.Name] = $entry.Value
            [Environment]::SetEnvironmentVariable($entry.Name, $null, 'Process')
        }
        try {
            $qdrantProcess = Start-Process -FilePath $qdrant -ArgumentList @('--config-path', ('"' + $config + '"')) `
                -WorkingDirectory $script:WorkspaceRoot -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput (Get-LocalPath '.runtime/logs/qdrant.stdout.log') `
                -RedirectStandardError (Get-LocalPath '.runtime/logs/qdrant.stderr.log')
        } finally {
            foreach ($name in $qdrantEnvironment.Keys) {
                [Environment]::SetEnvironmentVariable($name, $qdrantEnvironment[$name], 'Process')
            }
        }
        Save-OwnedProcess 'qdrant' $qdrantProcess $qdrant $config
    }
    Wait-LocalHealth 'http://127.0.0.1:6333/readyz' $qdrantProcess
    Write-Host 'Qdrant ready on 127.0.0.1:6333 (HTTP) and 127.0.0.1:6334 (gRPC).'
    if ($QdrantOnly) { return }

    $java = Get-LocalJava
    $jar = Get-LocalPath 'java-chatbot/target/java-chatbot-1.0.0.jar'
    $appProcess = Get-OwnedProcess 'app' $java $jar
    if ($null -eq $appProcess) {
        Assert-LocalPortsAvailable @(8090)
        if (-not $SkipBuild) {
            $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
            if ($null -eq $maven) { throw 'Maven (mvn.cmd) is required on PATH, or use -SkipBuild with an existing jar.' }
            $previousPath = $env:PATH
            try {
                $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $previousPath
                & $maven.Source '-gs' (Get-LocalPath 'infra/maven-settings.xml') `
                    '-s' (Get-LocalPath 'infra/maven-settings.xml') '-f' (Get-LocalPath 'java-chatbot/pom.xml') `
                    ('-Dmaven.repo.local=' + (Get-LocalPath '.runtime/m2')) 'package'
                if ($LASTEXITCODE -ne 0) { throw 'Maven build failed; application was not started.' }
            } finally { $env:PATH = $previousPath }
        }
        if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Application jar is missing. Run without -SkipBuild.' }
        $env:RAG_DATA_DIR = Get-LocalPath '.runtime/data'
        $env:RAG_JDBC_URL = 'jdbc:h2:file:' + ($env:RAG_DATA_DIR.Replace('\', '/') + '/metadata;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH')
        $env:RAG_QDRANT_HOST = '127.0.0.1'
        $env:RAG_QDRANT_GRPC_PORT = '6334'
        $env:RAG_QDRANT_URL = 'http://127.0.0.1:6333'
        $env:RAG_QDRANT_API_KEY = ''
        $env:SERVER_ADDRESS = '127.0.0.1'
        $env:SERVER_PORT = '8090'
        $env:SPRING_PROFILES_ACTIVE = 'local'
        $env:RAG_TUTORIAL_ENABLED = 'true'
        $appProcess = Start-Process -FilePath $java -ArgumentList @('-jar', ('"' + $jar + '"')) `
            -WorkingDirectory $script:WorkspaceRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Get-LocalPath '.runtime/logs/app.stdout.log') `
            -RedirectStandardError (Get-LocalPath '.runtime/logs/app.stderr.log')
        Save-OwnedProcess 'app' $appProcess $java $jar
    }
    Wait-LocalHealth 'http://127.0.0.1:8090/actuator/health' $appProcess 90
    Write-Host 'Application ready: http://127.0.0.1:8090'
    Write-Host 'Logs: .runtime/logs. Stop with scripts/Stop-Local.ps1.'
} finally {
    $controlLock.Dispose()
}
