<#
.SYNOPSIS
Builds the portal image from clean checkouts of the committed code and packages
it for a server with no registry and no internet.

.DESCRIPTION
Clones carp-dsp-portal, carp-dsp, carp.core-kotlin and health-workflow-interfaces
at their current commits, builds the image with no cache, and writes
carp-dsp-portal-<tag>.tar.gz: the image, the compose file, an .env template, the
UbiLife bundles, the commits it was built from and checksums. Uncommitted
changes are not included; the script stops if a checkout has any.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File deploy\package.ps1 -Tag 2026.10.08
#>
param(
    [string]$Tag = (Get-Date -Format 'yyyy.MM.dd'),
    # The folder holding the four checkouts.
    [string]$Code = (Resolve-Path "$PSScriptRoot\..\..").Path,
    [string]$Out = (Join-Path (Resolve-Path "$PSScriptRoot\..\..").Path 'carp-dsp-release')
)

$ErrorActionPreference = 'Stop'
$Repos = 'carp-dsp-portal', 'carp-dsp', 'carp.core-kotlin', 'health-workflow-interfaces'
$Bundles = 'ubilife', 'ubilife-compare', 'ubilife-compare-live', 'ubilife-accumulation'
$Image = "carp-dsp-portal:$Tag"

# Unix line endings: the server reads these files, and a CR would end up in values.
function Write-Lines([string]$Path, [string[]]$Lines) {
    [IO.File]::WriteAllText($Path, (($Lines -join "`n") + "`n"))
}

function Invoke-Native([string]$What, [scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit $LASTEXITCODE)" }
}

# 1. Every checkout committed, so the image is exactly the commits recorded below.
foreach ($repo in $Repos) {
    $changes = git -C "$Code\$repo" status --porcelain --untracked-files=no
    if ($changes) { throw "$repo has uncommitted changes. Commit them first:`n$changes" }
}

# 2. Clean clones of those commits.
$src = Join-Path $Out "src-$Tag"
if (Test-Path $src) { Remove-Item -Recurse -Force $src }
New-Item -ItemType Directory -Force $src | Out-Null
$versions = @("carp-dsp-portal $Tag, built $(Get-Date -Format s)", '')
foreach ($repo in $Repos) {
    Invoke-Native "clone $repo" { git clone --quiet --no-hardlinks "$Code\$repo" "$src\$repo" }
    $commit = git -C "$src\$repo" rev-parse HEAD
    $branch = git -C "$src\$repo" rev-parse --abbrev-ref HEAD
    $versions += "$repo $branch $commit"
}

# 3. The image, with no cached layers.
Invoke-Native 'docker build' {
    docker build --no-cache --pull -f "$src\carp-dsp-portal\Dockerfile" -t $Image $src
}

# 4. The package.
$name = "carp-dsp-portal-$Tag"
$pkg = Join-Path $Out $name
if (Test-Path $pkg) { Remove-Item -Recurse -Force $pkg }
New-Item -ItemType Directory -Force "$pkg\bundles" | Out-Null

Invoke-Native 'docker save' { docker save -o "$pkg\image.tar" $Image }
Copy-Item "$src\carp-dsp-portal\deploy\docker-compose.yml", "$src\carp-dsp-portal\deploy\README.md" $pkg
Write-Lines "$pkg\.env.example" ((Get-Content "$src\carp-dsp-portal\deploy\.env.example") -replace '^TAG=.*', "TAG=$Tag")
foreach ($bundle in $Bundles) {
    # tar rather than Compress-Archive, whose zips can hold backslash paths.
    $dir = "$src\carp-dsp-portal\demo-bundles\$bundle"
    $items = (Get-ChildItem -Force $dir).Name
    Invoke-Native "zip $bundle" { tar -a -cf "$pkg\bundles\$bundle.zip" -C $dir @items }
}
Write-Lines "$pkg\VERSIONS.txt" $versions

$sums = Get-ChildItem -Recurse -Force -File $pkg | ForEach-Object {
    $hash = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLower()
    "$hash  $($_.FullName.Substring($pkg.Length + 1).Replace('\', '/'))"
}
Write-Lines "$pkg\SHA256SUMS" $sums

Invoke-Native 'tar' { tar -czf "$Out\$name.tar.gz" -C $Out $name }
Remove-Item -Recurse -Force $src

$size = '{0:N1} GB' -f ((Get-Item "$Out\$name.tar.gz").Length / 1GB)
Write-Host "Packaged $Out\$name.tar.gz ($size)"
