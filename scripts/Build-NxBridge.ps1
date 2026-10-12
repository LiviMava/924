[CmdletBinding()]
param([string] $NxHome = $env:UGII_BASE_DIR, [ValidatePattern('^[A-Za-z0-9_.-]+\.dll$')] [string] $OutputName = 'NxBridge.dll')
. (Join-Path $PSScriptRoot 'Local-Common.ps1')
if ([string]::IsNullOrWhiteSpace($NxHome)) { throw 'Set UGII_BASE_DIR or pass -NxHome for NX 2512.' }
$managed = Join-Path $NxHome 'NXBIN/managed'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
foreach ($required in @($compiler, (Join-Path $managed 'NXOpen.dll'), (Join-Path $managed 'NXOpen.UF.dll'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required NX/.NET file missing: $required" }
}
$output = Get-LocalPath '.runtime/nx-bridge'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& $compiler /nologo /target:library /platform:x64 /optimize+ ('/out:' + (Join-Path $output $OutputName)) `
    ('/reference:' + (Join-Path $managed 'NXOpen.dll')) ('/reference:' + (Join-Path $managed 'NXOpen.UF.dll')) `
    ('/reference:' + (Join-Path $managed 'NXOpen.Utilities.dll')) `
    /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll `
    (Get-LocalPath 'nx-bridge/NxBridge.cs')
if ($LASTEXITCODE -ne 0) { throw 'NX bridge compilation failed.' }
Write-Host ('NX bridge compiled against installed NX assemblies. Load .runtime/nx-bridge/' + $OutputName + ' inside NX and enable its checkbox.')
