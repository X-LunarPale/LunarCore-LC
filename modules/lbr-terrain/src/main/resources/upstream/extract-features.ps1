param(
    [Parameter(Mandatory=$true)][string]$ModJar
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$expected = '3fa046943f05d58b5dfc486d8dd7a073085b81aec5c96692e7626b87cb64ab295b5e6a3214f6ccc28746b1eddbcc16360d1c7adee9f97ad29126ac7daca0a257'
if ((Get-FileHash -LiteralPath $ModJar -Algorithm SHA512).Hash.ToLowerInvariant() -ne $expected) {
    throw 'The input must be the pinned official Tectonic 3.0.29 Fabric 26.3 artifact.'
}
$features = [ordered]@{
    sha512 = $expected
    features = [ordered]@{}
    placedFeatures = [ordered]@{}
    oreFeatures = [ordered]@{}
    carvers = [ordered]@{}
    modifiers = [ordered]@{}
    densityOverlays = [ordered]@{}
}
$mod = [IO.Compression.ZipFile]::OpenRead($ModJar)
try {
    foreach ($entry in $mod.Entries) {
        $section = $null
        $key = $null
        if ($entry.FullName -match '^resourcepacks/tectonic/data/([^/]+)/worldgen/feature/(.+)\.json$') {
            $section = 'features'
            $key = "$($Matches[1]):$($Matches[2])"
        } elseif ($entry.FullName -match '^resourcepacks/tectonic/data/([^/]+)/worldgen/placed_feature/(.+)\.json$') {
            $section = 'placedFeatures'
            $key = "$($Matches[1]):$($Matches[2])"
        } elseif ($entry.FullName -match '^resourcepacks/tectonic/overlay.ore_fix/data/([^/]+)/worldgen/placed_feature/(.+)\.json$') {
            $section = 'oreFeatures'
            $key = "$($Matches[1]):$($Matches[2])"
        } elseif ($entry.FullName -match '^resourcepacks/tectonic/overlay.no_carvers/data/minecraft/worldgen/carver/(.+)\.json$') {
            $section = 'carvers'
            $key = "minecraft:$($Matches[1])"
        } elseif ($entry.FullName -match '^resourcepacks/tectonic/data/([^/]+)/lithostitched/worldgen_modifier/(.+)\.json$') {
            $section = 'modifiers'
            $key = "$($Matches[1]):$($Matches[2])"
        } elseif ($entry.FullName -match '^resourcepacks/tectonic/overlay.ultrasmooth/data/([^/]+)/worldgen/density_function/(.+)\.json$') {
            $section = 'densityOverlays'
            $key = "$($Matches[1]):$($Matches[2])"
        }
        if ($null -ne $section) {
            $reader = [IO.StreamReader]::new($entry.Open())
            try { $features[$section][$key] = $reader.ReadToEnd() | ConvertFrom-Json -AsHashtable }
            finally { $reader.Dispose() }
        }
    }
} finally { $mod.Dispose() }
$json = ConvertTo-Json -InputObject $features -Depth 100 -Compress
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'tectonic-features.json'), $json, [Text.UTF8Encoding]::new($false))
