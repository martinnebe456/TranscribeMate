"""Local transcript summarization helpers."""

from __future__ import annotations

import logging
import re
from pathlib import Path
from threading import Event
from typing import Callable

from ..core.files import sanitize_filename, unique_path
from ..core.hf_progress import huggingface_download_progress

LOGGER = logging.getLogger(__name__)

SUMMARY_TIER_MODELS: dict[str, str] = {
    "low": "Qwen/Qwen2.5-0.5B-Instruct",
    "medium": "Qwen/Qwen2.5-1.5B-Instruct",
    "high": "Qwen/Qwen2.5-7B-Instruct",
}
SUMMARY_LANGUAGE_NAMES: dict[str, str] = {
    "auto": "auto",
    "cs": "Czech",
    "en": "English",
    "de": "German",
    "es": "Spanish",
    "fr": "French",
    "it": "Italian",
    "ja": "Japanese",
    "ko": "Korean",
    "nl": "Dutch",
    "pl": "Polish",
    "pt": "Portuguese",
    "ru": "Russian",
    "tr": "Turkish",
    "uk": "Ukrainian",
    "zh": "Chinese",
}
SUMMARY_SECTION_TITLES: tuple[str, ...] = (
    "TL;DR",
    "Key Points",
    "Action Items",
    "Open Questions",
    "Risks",
)

SummaryProgressCallback = Callable[[str, float | None, float | None, bool], None]
SummaryLogCallback = Callable[[str], None]
SummaryGenerateCallback = Callable[[str, int], str]


def supported_summary_tiers() -> tuple[str, ...]:
    return tuple(SUMMARY_TIER_MODELS.keys())


def model_for_summary_tier(tier: str) -> str:
    normalized = str(tier or "").strip().lower()
    model = SUMMARY_TIER_MODELS.get(normalized)
    if not model:
        raise ValueError(
            f"Unsupported summary_ai_tier '{tier}'. Supported values: {', '.join(supported_summary_tiers())}."
        )
    return model


def split_transcript_text(text: str, *, max_chars: int = 12000) -> list[str]:
    normalized = str(text or "").strip()
    if not normalized:
        return []

    lines = normalized.splitlines()
    chunks: list[str] = []
    current: list[str] = []
    current_len = 0

    for raw_line in lines:
        line = raw_line.rstrip()
        if not line and not current:
            continue
        line_len = len(line) + 1
        if current and current_len + line_len > max_chars:
            chunk_text = "\n".join(current).strip()
            if chunk_text:
                chunks.append(chunk_text)
            current = []
            current_len = 0
        if line:
            current.append(line)
            current_len += line_len

    if current:
        chunk_text = "\n".join(current).strip()
        if chunk_text:
            chunks.append(chunk_text)

    if chunks:
        return chunks

    return [normalized[idx: idx + max_chars].strip() for idx in range(0, len(normalized), max_chars) if normalized[idx: idx + max_chars].strip()]


def summarize_transcript_file(
    *,
    transcript_path: Path,
    output_dir: Path,
    summary_lang: str = "auto",
    summary_ai_tier: str = "medium",
    log: SummaryLogCallback | None = None,
    progress: SummaryProgressCallback | None = None,
    stop_flag: Event | None = None,
    generate_text_fn: SummaryGenerateCallback | None = None,
) -> Path:
    source_path = Path(transcript_path).expanduser().resolve()
    if not source_path.is_file():
        raise RuntimeError(f"Transcript file not found: {source_path}")
    if source_path.suffix.lower() != ".txt":
        raise RuntimeError("summarize_transcript accepts only '.txt' transcript files.")

    summary_tier = str(summary_ai_tier or "medium").strip().lower() or "medium"
    model_name = model_for_summary_tier(summary_tier)
    normalized_lang = str(summary_lang or "auto").strip().lower() or "auto"

    _emit_progress(progress, step="prepare", overall_pct=0.0, step_pct=0.0, indeterminate=False)
    _log(log, f"[INFO] AI summary tier={summary_tier}, model={model_name}")

    transcript_text = source_path.read_text(encoding="utf-8").strip()
    if not transcript_text:
        raise RuntimeError("Transcript is empty. Nothing to summarize.")

    chunks = split_transcript_text(transcript_text)
    if not chunks:
        raise RuntimeError("Transcript is empty after normalization. Nothing to summarize.")
    _check_cancelled(stop_flag)

    generator = generate_text_fn
    if generator is None:
        generator = _create_generator(
            model_name=model_name,
            log=log,
            progress=lambda step, overall_pct, step_pct, indeterminate: _emit_progress(
                progress,
                step=step,
                overall_pct=overall_pct,
                step_pct=step_pct,
                indeterminate=indeterminate,
            ),
        )
    else:
        _emit_progress(progress, step="model_ready", overall_pct=20.0, step_pct=20.0, indeterminate=False)

    partial_summaries: list[str] = []
    if len(chunks) == 1:
        _log(log, "[INFO] Summarizing transcript in single pass.")
        prompt = _build_single_pass_prompt(chunks[0], summary_lang=normalized_lang)
        raw_summary = generator(prompt, 1200)
        final_summary = _normalize_summary_markdown(raw_summary)
        _emit_progress(progress, step="synthesize", overall_pct=92.0, step_pct=92.0, indeterminate=False)
    else:
        _log(log, f"[INFO] Transcript split into {len(chunks)} chunk(s). Running hierarchical summary.")
        for index, chunk in enumerate(chunks, start=1):
            _check_cancelled(stop_flag)
            chunk_prompt = _build_chunk_prompt(
                chunk,
                summary_lang=normalized_lang,
                chunk_index=index,
                chunk_total=len(chunks),
            )
            partial = generator(chunk_prompt, 700).strip()
            if not partial:
                raise RuntimeError(f"Model returned empty summary for chunk {index}/{len(chunks)}.")
            partial_summaries.append(partial)

            chunk_ratio = index / float(len(chunks))
            overall = 20.0 + (chunk_ratio * 60.0)
            _emit_progress(progress, step="summarize_chunks", overall_pct=overall, step_pct=overall, indeterminate=False)
            _log(log, f"[INFO] Chunk {index}/{len(chunks)} summarized.")

        _check_cancelled(stop_flag)
        synthesis_prompt = _build_synthesis_prompt(partial_summaries, summary_lang=normalized_lang)
        raw_summary = generator(synthesis_prompt, 1200)
        final_summary = _normalize_summary_markdown(raw_summary)
        _emit_progress(progress, step="synthesize", overall_pct=94.0, step_pct=94.0, indeterminate=False)

    if normalized_lang != "auto":
        _check_cancelled(stop_flag)
        _log(log, f"[INFO] Enforcing summary output language: {normalized_lang}")
        _emit_progress(progress, step="enforce_language", overall_pct=96.0, step_pct=96.0, indeterminate=False)
        rewrite_prompt = _build_language_rewrite_prompt(final_summary, summary_lang=normalized_lang)
        rewritten_summary = generator(rewrite_prompt, 1200)
        final_summary = _normalize_summary_markdown(rewritten_summary)
        _emit_progress(progress, step="enforce_language", overall_pct=98.0, step_pct=98.0, indeterminate=False)

    _check_cancelled(stop_flag)
    output_path = _summary_output_path(
        output_dir=Path(output_dir),
        transcript_path=source_path,
        summary_lang=normalized_lang,
    )
    output_path.write_text(final_summary + "\n", encoding="utf-8")
    _emit_progress(progress, step="write_output", overall_pct=100.0, step_pct=100.0, indeterminate=False)
    _log(log, f"[OK] AI summary saved: {output_path}")
    return output_path


def _build_single_pass_prompt(transcript_text: str, *, summary_lang: str) -> str:
    return (
        "You are a factual transcript summarizer for meetings, interviews, lectures, conference talks, and videos.\n"
        f"{_language_instruction(summary_lang)}\n\n"
        "Return Markdown using exactly these sections:\n"
        "## TL;DR\n"
        "## Key Points\n"
        "## Action Items\n"
        "## Open Questions\n"
        "## Risks\n\n"
        "Rules:\n"
        "- Use only transcript information.\n"
        "- Do not hallucinate decisions, names, or numbers.\n"
        "- Capture the important points so a reader understands what happened without reading full transcript.\n"
        "- Keep wording concrete and concise.\n"
        "- Prefer complete, specific bullets over vague statements.\n"
        "- If a section has no supporting evidence, write '- N/A'.\n\n"
        "Section guidance:\n"
        "- TL;DR: 2-4 sentences with the main outcome and topic.\n"
        "- Key Points: major facts, arguments, explanations, and conclusions.\n"
        "- Action Items: explicit tasks/owners/deadlines only if present.\n"
        "- Open Questions: unresolved points, unknowns, or pending clarifications.\n"
        "- Risks: blockers, constraints, concerns, or failure modes.\n\n"
        "Transcript:\n"
        f"{transcript_text}\n"
    )


def _build_chunk_prompt(
    transcript_chunk: str,
    *,
    summary_lang: str,
    chunk_index: int,
    chunk_total: int,
) -> str:
    return (
        "You are extracting factual summary points from one chunk of a long transcript.\n"
        f"{_language_instruction(summary_lang)}\n"
        f"Chunk: {chunk_index}/{chunk_total}\n\n"
        "Return Markdown using exactly these sections:\n"
        "## TL;DR\n"
        "## Key Points\n"
        "## Action Items\n"
        "## Open Questions\n"
        "## Risks\n\n"
        "Rules:\n"
        "- Focus only on this chunk content.\n"
        "- Do not infer facts not present in this chunk.\n"
        "- Keep only key information; skip filler/small talk.\n"
        "- If a section is not supported in this chunk, write '- N/A'.\n\n"
        "Chunk transcript:\n"
        f"{transcript_chunk}\n"
    )


def _build_synthesis_prompt(partial_summaries: list[str], *, summary_lang: str) -> str:
    joined = "\n\n---\n\n".join(partial_summaries)
    return (
        "You are consolidating chunk-level transcript summaries into one final summary.\n"
        f"{_language_instruction(summary_lang)}\n\n"
        "Return Markdown using exactly these sections:\n"
        "## TL;DR\n"
        "## Key Points\n"
        "## Action Items\n"
        "## Open Questions\n"
        "## Risks\n\n"
        "Rules:\n"
        "- Keep only facts present in the chunk summaries.\n"
        "- Deduplicate repeated points and keep the final result coherent.\n"
        "- Preserve important cross-chunk context and outcomes.\n"
        "- Keep concise but complete for major points.\n"
        "- If a section is unsupported, write '- N/A'.\n\n"
        "Chunk summaries:\n"
        f"{joined}\n"
    )


def _language_instruction(summary_lang: str) -> str:
    language = str(summary_lang or "").strip().lower()
    if not language or language == "auto":
        return (
            "Output language requirement: write section content in the dominant language used by the transcript. "
            "Keep section headings exactly as specified (English headings only)."
        )
    language_name = SUMMARY_LANGUAGE_NAMES.get(language, f"language code `{language}`")
    return (
        f"Output language requirement (MANDATORY): write all section content in {language_name} (code `{language}`). "
        "If transcript language differs, translate summary content to the required language. "
        "Do not keep content in source language unless it is the same as required. "
        "Keep section headings exactly as specified (English headings only)."
    )


def _build_language_rewrite_prompt(markdown_summary: str, *, summary_lang: str) -> str:
    language = str(summary_lang or "").strip().lower() or "auto"
    if language == "auto":
        return markdown_summary
    language_name = SUMMARY_LANGUAGE_NAMES.get(language, f"language code `{language}`")
    return (
        "Rewrite this Markdown summary into the required target language.\n"
        f"Target language: {language_name} (code `{language}`).\n\n"
        "Hard rules:\n"
        "- Keep section headings exactly unchanged:\n"
        "  ## TL;DR\n"
        "  ## Key Points\n"
        "  ## Action Items\n"
        "  ## Open Questions\n"
        "  ## Risks\n"
        "- Keep facts and meaning unchanged.\n"
        "- Do not add new information.\n"
        "- Translate only section bodies.\n"
        "- Return Markdown only.\n\n"
        "Summary to rewrite:\n"
        f"{markdown_summary}\n"
    )


def _summary_output_path(*, output_dir: Path, transcript_path: Path, summary_lang: str) -> Path:
    normalized_output_dir = output_dir.expanduser().resolve()
    lang_token = sanitize_filename(summary_lang if summary_lang and summary_lang != "auto" else "auto").lower()
    stem = sanitize_filename(transcript_path.stem)
    filename = f"{stem}.summary.{lang_token}.md"
    return unique_path(normalized_output_dir, filename)


def _create_generator(
    *,
    model_name: str,
    log: SummaryLogCallback | None,
    progress: SummaryProgressCallback | None,
) -> SummaryGenerateCallback:
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    _emit_progress(progress, step="model_init", overall_pct=2.0, step_pct=2.0, indeterminate=False)
    _log(log, f"[INFO] Loading summary model: {model_name}")

    def _download_progress(desc: str, percent: float | None):
        if percent is None:
            return
        bounded = max(0.0, min(100.0, float(percent)))
        mapped = 2.0 + (bounded * 0.12)
        _emit_progress(progress, step=f"download:{desc}", overall_pct=mapped, step_pct=mapped, indeterminate=False)

    with huggingface_download_progress(_download_progress):
        tokenizer = AutoTokenizer.from_pretrained(model_name)
        model = AutoModelForCausalLM.from_pretrained(
            model_name,
            torch_dtype="auto",
        )

    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = model.to(device)
    model.eval()

    if tokenizer.pad_token_id is None and tokenizer.eos_token_id is not None:
        tokenizer.pad_token = tokenizer.eos_token
    pad_token_id = tokenizer.pad_token_id if tokenizer.pad_token_id is not None else tokenizer.eos_token_id
    max_prompt_tokens = _safe_model_context(getattr(tokenizer, "model_max_length", 4096))

    _log(log, f"[INFO] Summary model ready on device={device}.")
    _emit_progress(progress, step="model_ready", overall_pct=20.0, step_pct=20.0, indeterminate=False)

    def _generate(prompt: str, max_new_tokens: int) -> str:
        prompt_text = _render_prompt_for_tokenizer(tokenizer, prompt)
        encoded = tokenizer(
            prompt_text,
            return_tensors="pt",
            truncation=True,
            max_length=max_prompt_tokens,
        )
        encoded = {key: value.to(device) for key, value in encoded.items()}
        with torch.no_grad():
            generated = model.generate(
                **encoded,
                max_new_tokens=max(128, int(max_new_tokens)),
                do_sample=False,
                temperature=0.0,
                pad_token_id=pad_token_id,
                eos_token_id=tokenizer.eos_token_id,
            )

        input_len = int(encoded["input_ids"].shape[-1])
        generated_tokens = generated[0][input_len:]
        text = tokenizer.decode(generated_tokens, skip_special_tokens=True).strip()
        if not text:
            text = tokenizer.decode(generated[0], skip_special_tokens=True).strip()
        if not text:
            raise RuntimeError("Model returned empty summary output.")
        return text

    return _generate


def _render_prompt_for_tokenizer(tokenizer, user_prompt: str) -> str:
    system_message = (
        "You are a factual assistant that summarizes transcripts strictly from provided content. "
        "Use Markdown sections exactly as requested. "
        "Never hallucinate facts. Always follow the requested output language."
    )
    messages = [
        {"role": "system", "content": system_message},
        {"role": "user", "content": user_prompt},
    ]
    if hasattr(tokenizer, "apply_chat_template"):
        try:
            return tokenizer.apply_chat_template(
                messages,
                tokenize=False,
                add_generation_prompt=True,
            )
        except Exception:
            LOGGER.debug("Failed to use chat template, falling back to plain prompt.", exc_info=True)
    return f"{system_message}\n\n{user_prompt}"


def _safe_model_context(raw_limit: int | float | None) -> int:
    try:
        value = int(raw_limit or 0)
    except Exception:
        value = 0
    if value <= 0 or value > 32768:
        return 4096
    return max(1024, min(value, 4096))


def _normalize_summary_markdown(raw_text: str) -> str:
    text = str(raw_text or "").strip()
    if not text:
        return _empty_summary_markdown()

    extracted = _extract_sections(text)
    if not any(extracted.values()):
        extracted["TL;DR"] = text

    lines: list[str] = []
    for title in SUMMARY_SECTION_TITLES:
        body = str(extracted.get(title, "") or "").strip()
        if not body:
            body = "- N/A"
        elif title != "TL;DR":
            body = _normalize_bullets(body)
        lines.extend((f"## {title}", "", body, ""))
    return "\n".join(lines).strip()


def _extract_sections(markdown_text: str) -> dict[str, str]:
    sections: dict[str, list[str]] = {title: [] for title in SUMMARY_SECTION_TITLES}
    current: str | None = None

    for raw_line in markdown_text.splitlines():
        line = raw_line.rstrip()
        heading_match = re.match(r"^\s{0,3}#{1,6}\s*(.+?)\s*$", line)
        if heading_match:
            heading = _match_section_heading(heading_match.group(1))
            if heading:
                current = heading
                continue
        if current:
            sections[current].append(line)

    return {title: "\n".join(lines).strip() for title, lines in sections.items()}


def _match_section_heading(value: str) -> str | None:
    normalized = re.sub(r"[^a-z0-9]+", "", str(value or "").strip().lower())
    mapping = {
        "tldr": "TL;DR",
        "keypoints": "Key Points",
        "actionitems": "Action Items",
        "openquestions": "Open Questions",
        "risks": "Risks",
    }
    return mapping.get(normalized)


def _normalize_bullets(text: str) -> str:
    lines = [line.strip() for line in str(text or "").splitlines() if line.strip()]
    if not lines:
        return "- N/A"
    normalized: list[str] = []
    for line in lines:
        if line.startswith(("- ", "* ", "• ")):
            normalized.append("- " + line[2:].strip())
        else:
            normalized.append("- " + line)
    return "\n".join(normalized)


def _empty_summary_markdown() -> str:
    lines: list[str] = []
    for title in SUMMARY_SECTION_TITLES:
        lines.extend((f"## {title}", "", "- N/A", ""))
    return "\n".join(lines).strip()


def _emit_progress(
    callback: SummaryProgressCallback | None,
    *,
    step: str,
    overall_pct: float | None,
    step_pct: float | None,
    indeterminate: bool,
) -> None:
    if callback is None:
        return
    callback(step, overall_pct, step_pct, bool(indeterminate))


def _log(callback: SummaryLogCallback | None, line: str) -> None:
    if callback is None:
        return
    text = str(line or "").strip()
    if text:
        callback(text)


def _check_cancelled(stop_flag: Event | None) -> None:
    if stop_flag is not None and stop_flag.is_set():
        raise RuntimeError("Summary generation cancelled by user.")
