param(
    [Parameter(Mandatory = $true)][string]$PaperJar,
    [string]$AgentJar = "$PSScriptRoot/terrain-agent/build/libs/LC-Terrain-Agent.jar",
    [string]$Java = "java",
    [string]$Memory = "4G",
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$ServerArguments = @("--nogui")
)

$paperPath = (Resolve-Path -LiteralPath $PaperJar -ErrorAction Stop).Path
$agentPath = (Resolve-Path -LiteralPath $AgentJar -ErrorAction Stop).Path
Push-Location (Split-Path -Parent $paperPath)
try {
    & $Java "-Xmx$Memory" "-javaagent:$agentPath" -jar $paperPath @ServerArguments
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
