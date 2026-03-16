$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "windows\bootstrap_runtime.ps1"
& $script @args
exit $LASTEXITCODE
