$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "windows\run_v2_backend.ps1"
& $script @args
exit $LASTEXITCODE
