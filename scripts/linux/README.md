# Linux Script Layout

This directory is reserved for the future Linux port.

Planned platform-local entrypoints:
- `run_v2_backend.sh`
- `run_v2_frontend.sh`
- `bootstrap_runtime.sh`
- `build_v2_release.sh`

Planned output layout:
- `dist/linux/`

Top-level wrapper scripts in `scripts/` are kept as compatibility shims and can
dispatch to `scripts/linux/` once the Linux implementation is added.
