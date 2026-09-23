[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'Local-Common.ps1')
Initialize-LocalDirectories
$installLock = Enter-LocalLock 'install'
try {
    $version = '1.19.1'
    $expectedHash = '9b6f69bd85f6abed4bc13f943099f55c6ffd55f5dd90388635320d8fbb569eb0'
    $url = "https://github.com/qdrant/qdrant/releases/download/v$version/qdrant-x86_64-pc-windows-msvc.zip"
    $archive = Get-LocalPath ".runtime/downloads/qdrant-v$version-windows-x64.zip"
    $executable = Get-LocalPath '.runtime/qdrant/qdrant.exe'
    $manifestPath = Get-LocalPath '.runtime/qdrant/install.json'
    if (Test-Path -LiteralPath $executable -PathType Leaf) {
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
            throw 'An untracked qdrant.exe exists. Refusing to replace it.'
        }
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        if ($manifest.version -ne $version -or $manifest.archiveSha256 -ne $expectedHash -or
                (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash -ne $manifest.executableSha256) {
            throw 'Installed Qdrant does not match the pinned version/hash. Refusing to overwrite it.'
        }
        Write-Host "Qdrant $version is already installed and verified."
        return
    }
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
        Write-Host "Downloading official Qdrant $version for Windows x64..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $partial = Assert-WorkspacePath ($archive + '.partial')
        Invoke-WebRequest -Uri $url -OutFile $partial -UseBasicParsing
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $expectedHash) {
            throw 'Downloaded archive failed the pinned SHA-256 check.'
        }
        Move-Item -LiteralPath $partial -Destination $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expectedHash) {
        throw 'Cached Qdrant archive failed the pinned SHA-256 check.'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath (Get-LocalPath '.runtime/qdrant')
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw 'Qdrant executable was missing from the archive.' }
    @{
        version = $version
        source = $url
        archiveSha256 = $expectedHash
        executableSha256 = (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash.ToLowerInvariant()
    } | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Write-Host "Installed and SHA-256 verified Qdrant $version."
} finally {
    $installLock.Dispose()
}
