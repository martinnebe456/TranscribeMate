$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "windows\run_v2_frontend.ps1"
& $script @args
exit $LASTEXITCODE
