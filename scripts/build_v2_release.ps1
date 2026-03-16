$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "windows\build_v2_release.ps1"
& $script @args
exit $LASTEXITCODE
