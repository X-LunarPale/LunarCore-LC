param(
    [Parameter(Mandatory=$true)][string]$ModJar
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$expected = '3fa046943f05d58b5dfc486d8dd7a073085b81aec5c96692e7626b87cb64ab295b5e6a3214f6ccc28746b1eddbcc16360d1c7adee9f97ad29126ac7daca0a257'
if ((Get-FileHash -LiteralPath $ModJar -Algorithm SHA512).Hash.ToLowerInvariant() -ne $expected) {
    throw 'The input must be the pinned official Tectonic 3.0.29 Fabric 26.3 artifact.'
}
$model = [ordered]@{
    upstream = [ordered]@{
        name = 'Tectonic'
        version = '3.0.29'
        gameVersion = '26.3'
        author = 'Apollo'
        license = 'MIT'
        url = 'https://cdn.modrinth.com/data/lWDHr9jE/versions/irrtYKCq/tectonic-3.0.29-fabric-26.3.jar'
        sha512 = $expected
    }
    densityFunctions = [ordered]@{}
    noises = [ordered]@{}
    materialRules = [ordered]@{}
    noiseSettings = $null
}
function Read-Entry($entry) {
    $reader = [IO.StreamReader]::new($entry.Open())
    try { return ($reader.ReadToEnd() | ConvertFrom-Json -AsHashtable) }
    finally { $reader.Dispose() }
}
$mod = [IO.Compression.ZipFile]::OpenRead($ModJar)
try {
    foreach ($entry in $mod.Entries) {
        if ($entry.FullName -match '^resourcepacks/tectonic/data/([^/]+)/worldgen/density_function/(.+)\.json$') {
            $model.densityFunctions["$($Matches[1]):$($Matches[2])"] = Read-Entry $entry
        } elseif ($entry.FullName -match '^resourcepacks/tectonic/data/([^/]+)/worldgen/noise/(.+)\.json$') {
            $model.noises["$($Matches[1]):$($Matches[2])"] = Read-Entry $entry
        } elseif ($entry.FullName -match '^resourcepacks/tectonic/data/([^/]+)/worldgen/material_rule/(.+)\.json$') {
            $model.materialRules["$($Matches[1]):$($Matches[2])"] = Read-Entry $entry
        } elseif ($entry.FullName -eq 'resourcepacks/tectonic/data/minecraft/worldgen/noise_settings/overworld.json') {
            $model.noiseSettings = Read-Entry $entry
        }
    }
} finally { $mod.Dispose() }
if ($null -eq $model.noiseSettings) { throw 'Missing mod noise settings' }
$json = ConvertTo-Json -InputObject $model -Depth 100 -Compress
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'tectonic-model.json'), $json, [Text.UTF8Encoding]::new($false))
