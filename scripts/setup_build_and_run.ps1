$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "windows\setup_build_and_run.ps1"
& $script @args
exit $LASTEXITCODE
