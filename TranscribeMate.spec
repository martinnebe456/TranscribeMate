# -*- mode: python ; coding: utf-8 -*-

from pathlib import Path

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
