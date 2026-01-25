# -*- mode: python ; coding: utf-8 -*-

from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files, collect_submodules

block_cipher = None

root = Path.cwd()
datas = []
for name in ["config.example.json", "DISCLAIMER.md", "LICENSE", "README.md"]:
    p = root / name
    if p.exists():
        datas.append((str(p), "."))

assets_keep = root / "assets" / ".gitkeep"
if assets_keep.exists():
    datas.append((str(assets_keep), "assets"))

hiddenimports = [
    "transcribemate",
    "transcribemate.runtime",
    "transcribemate.runtime.runtime",
    "transcribemate.ui",
    "transcribemate.ui.ui",
    "transcribemate.pipeline",
    "transcribemate.pipeline.download",
    "transcribemate.pipeline.transcribe",
    "transcribemate.pipeline.translate",
    "transcribemate.pipeline.subtitles",
    "transcribemate.core",
    "transcribemate.core.paths",
    "transcribemate.core.config",
    "transcribemate.core.files",
    "transcribemate.core.gpu",
    "transcribemate.core.i18n",
    "transcribemate.core.models",
    "transcribemate.core.process",
    "transcribemate.core.transcripts",
    "transcribemate.core.types",
    "ttkbootstrap",
    "tkinterdnd2",
    "win10toast",
    "yt_dlp",
    "faster_whisper",
    "transformers",
    "sentencepiece",
    "accelerate",
    "safetensors",
    "ctranslate2",
    "av",
    "pip",
    "ensurepip",
]

# Torch pulls some stdlib modules dynamically; include a safety net for frozen builds.
stdlib_imports = [
    "timeit",
    "pickletools",
    "pstats",
    "profile",
    "cProfile",
    "inspect",
    "pydoc",
    "multiprocessing",
    "multiprocessing.pool",
    "multiprocessing.context",
    "concurrent.futures",
    "concurrent.futures.thread",
    "concurrent.futures.process",
    "asyncio",
    "asyncio.events",
    "asyncio.base_events",
    "queue",
    "threading",
    "logging",
    "traceback",
    "contextlib",
    "importlib",
    "importlib.resources",
    "importlib.metadata",
    "pkgutil",
    "ctypes",
    "ctypes.util",
    "signal",
    "subprocess",
    "socket",
    "selectors",
]

hiddenimports += stdlib_imports



try:
    # Bundle pip internals so runtime installs work in the frozen EXE.
    hiddenimports += collect_submodules("pip")
    hiddenimports += collect_submodules("ensurepip")
except Exception:
    pass

try:
    # Ensure vendored pip data (for example distlib executables) is present.
    datas += collect_data_files("pip")
    datas += collect_data_files("ensurepip")
except Exception:
    pass

module_collection_mode = {
    # pip expects a real filesystem package path for its vendored modules.
    "pip": "py",
    "ensurepip": "py",
}

a = Analysis(
    ["main.py"],
    pathex=[str(root)],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["torch", "torchvision", "torchaudio", "tensorboard", "triton"],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
    module_collection_mode=module_collection_mode,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="TranscribeMate",
    icon=str(root / "icon.ico"),
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,
    disable_windowed_traceback=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name="TranscribeMate",
)
