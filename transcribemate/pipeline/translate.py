"""Subtitle translation helpers."""

from __future__ import annotations

import logging
import re
from pathlib import Path

LOGGER = logging.getLogger(__name__)
_SPEAKER_PREFIX_PATTERN = re.compile(r"^(?P<prefix>[^:\n]{1,80}):\s*(?P<body>.+)$", re.DOTALL)


def _split_speaker_prefix(text: str) -> tuple[str, str]:
    cleaned = str(text or "").strip()
    match = _SPEAKER_PREFIX_PATTERN.match(cleaned)
    if not match:
        return "", cleaned
    prefix = match.group("prefix").strip()
    body = match.group("body").strip()
    if not body:
        return "", cleaned
    return f"{prefix}: ", body


def translate_srt(
    inp_srt: Path,
    out_srt: Path,
    model_name: str,
    prefer_gpu: bool,
    batch_size: int,
    log,
    set_step_progress,
    stop_flag,
):
    import torch
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    if batch_size < 1:
        LOGGER.warning("Invalid batch_size=%s, defaulting to 1", batch_size)
        batch_size = 1

    device = "cuda" if (prefer_gpu and torch.cuda.is_available()) else "cpu"
    log(f"[INFO] Translation settings: model={model_name}, device={device}, batch={batch_size}\n")

    log("[INFO] Loading translation tokenizer...\n")
    tok = AutoTokenizer.from_pretrained(model_name)

    log("[INFO] Loading translation model...\n")
    try:
        mdl = AutoModelForSeq2SeqLM.from_pretrained(
            model_name,
            use_safetensors=True,
            torch_dtype="auto",
        ).to(device)
    except (OSError, ValueError):
        log("[WARN] Safetensors not available, falling back to PyTorch format.\n")
        mdl = AutoModelForSeq2SeqLM.from_pretrained(
            model_name,
            torch_dtype="auto",
        ).to(device)

    srt = inp_srt.read_text(encoding="utf-8")
    blocks = re.split(r"\n{2,}", srt.strip())

    texts = []
    speaker_prefixes = []
    parsed = []
    for block in blocks:
        lines = block.splitlines()
        if len(lines) < 3:
            parsed.append((lines, None))
            continue
        idx = lines[0].strip()
        ts = lines[1].strip()
        text = "\n".join(lines[2:]).strip()
        parsed.append(([idx, ts], text))
        prefix, body = _split_speaker_prefix(text.replace("\n", " "))
        texts.append(body)
        speaker_prefixes.append(prefix)

    total = max(1, len(texts))
    log(f"[INFO] Subtitle lines to translate: {len(texts)}\n")

    out = []
    for i in range(0, len(texts), batch_size):
        if stop_flag.is_set():
            raise RuntimeError("Stopped by user.")
        batch = texts[i:i + batch_size]
        inputs = tok(batch, return_tensors="pt", padding=True, truncation=True).to(device)
        with torch.no_grad():
            gen = mdl.generate(**inputs, max_new_tokens=256)
        out.extend(tok.batch_decode(gen, skip_special_tokens=True))
        done = min(i + batch_size, len(texts))
        pct = done * 100.0 / total
        set_step_progress(pct)
        log(f"[INFO] Translation progress: {done}/{len(texts)} ({pct:.0f}%)\n")

    j = 0
    out_blocks = []
    for header, text in parsed:
        if text is None:
            out_blocks.append("\n".join(header))
        else:
            translated = out[j].strip()
            prefix = speaker_prefixes[j] if j < len(speaker_prefixes) else ""
            j += 1
            rendered = f"{prefix}{translated}".strip()
            out_blocks.append("\n".join([header[0], header[1], rendered]))

    out_srt.parent.mkdir(parents=True, exist_ok=True)
    out_srt.write_text("\n\n".join(out_blocks) + "\n", encoding="utf-8")
    log(f"[OK] Translated SRT saved: {out_srt}\n")
