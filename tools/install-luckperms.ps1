[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ServerDirectory
)

$ErrorActionPreference = "Stop"
$serverRoot = [System.IO.Path]::GetFullPath($ServerDirectory)
$pluginDirectory = Join-Path $serverRoot "plugins"
New-Item -ItemType Directory -Path $pluginDirectory -Force | Out-Null

$metadata = Invoke-RestMethod -Uri "https://metadata.luckperms.net/data/all" -TimeoutSec 30
if (-not $metadata.version -or -not $metadata.downloads.bukkit) {
    throw "The official LuckPerms service did not return a Bukkit download."
}

$target = Join-Path $pluginDirectory ("LuckPerms-Bukkit-" + $metadata.version + ".jar")
$temporary = $target + ".download"
try {
    Invoke-WebRequest -Uri $metadata.downloads.bukkit -OutFile $temporary -TimeoutSec 180
    if ((Get-Item -LiteralPath $temporary).Length -eq 0) {
        throw "The LuckPerms download is empty."
    }
    Move-Item -LiteralPath $temporary -Destination $target -Force
} finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary
    }
}

Get-ChildItem -LiteralPath $pluginDirectory -File |
    Where-Object { $_.Name -match "^LuckPerms-Bukkit-[0-9][0-9.]*\.jar$" -and $_.FullName -ne $target } |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName }

Write-Output ("Installed LuckPerms " + $metadata.version + ": " + $target)
Write-Output "Fully restart the server to load the plugin."
