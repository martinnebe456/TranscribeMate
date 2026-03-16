# Linux Script Layout

Linux x64 is now a first-class packaging target.

Platform-local entrypoints:
- `run_v2_backend.sh`
- `run_v2_frontend.sh`
- `bootstrap_runtime.sh`
- `build_v2_release.sh`

Release variants:
- `--distro debian`
- `--distro arch`

Output layout:
- `dist/linux/`
- `dist/linux/debian/TranscribeMate/`
- `dist/linux/arch/TranscribeMate/`
- `dist/linux/TranscribeMate-<version>-linux-debian-x64.tar.gz`
- `dist/linux/TranscribeMate-<version>-linux-arch-x64.tar.gz`

Top-level wrapper scripts in `scripts/` are kept as compatibility shims and can
dispatch to `scripts/linux/`.
