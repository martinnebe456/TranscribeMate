"""Request/response models for the V2 backend service."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ...core.i18n import LANG_CODES, TRANSLATION_MODELS

ALLOWED_SOURCE_MODES = {"local", "youtube"}
ALLOWED_OUTPUT_MODES = {"conference", "video_subs", "video_dub", "srt_only", "txt_only"}
ALLOWED_SUBTITLE_MODES = {"soft", "hard"}
ALLOWED_DIARIZATION_BACKENDS = {"stable_local", "advanced_pyannote"}
ALLOWED_MODULES = {
    "offline_transcribe",
    "youtube_transcribe",
    "speaker_transcribe",
    "conference_mode",
    "youtube_subtitles",
    "youtube_dub",
    "legacy",
}


class RequestValidationError(ValueError):
    """Raised when request payload is not valid."""

    def __init__(self, message: str, *, code: str = "invalid_request", details: Any = None):
        super().__init__(message)
        self.code = code
        self.details = details


def _as_bool(value: Any, *, default: bool = False) -> bool:
    if value is None:
        return bool(default)
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    text = str(value).strip().lower()
    if text in {"1", "true", "yes", "y", "on"}:
        return True
    if text in {"0", "false", "no", "n", "off", ""}:
        return False
    return bool(default)


def _as_int(
    value: Any,
    *,
    default: int,
    minimum: int | None = None,
    maximum: int | None = None,
) -> int:
    try:
        result = int(value)
    except Exception:
        result = int(default)
    if minimum is not None:
        result = max(minimum, result)
    if maximum is not None:
        result = min(maximum, result)
    return result


def _as_float(value: Any, *, default: float = 0.0) -> float:
    try:
        return float(value)
    except Exception:
        return float(default)


def _normalized_str(value: Any, *, default: str = "") -> str:
    text = str(value or "").strip()
    return text if text else default


def _normalize_target_lang(value: str) -> str:
    text = str(value or "").strip()
    if not text:
        return "en→cs"

    # Common fallback forms from ASCII/UI encodings.
    text = text.replace("â†’", "→").replace("->", "→").replace("=>", "→")
    text = text.replace("➜", "→").replace("➡", "→")
    text = "".join(text.split())

    if text in TRANSLATION_MODELS:
        return text

    lower = text.lower()
    # Normalize variants like "en-cs", "en/cs", "en|cs".
    import re

    match = re.match(r"^([a-z]{2})[^a-z]+([a-z]{2})$", lower)
    if match:
        canonical = f"{match.group(1)}→{match.group(2)}"
        if canonical in TRANSLATION_MODELS:
            return canonical

    return text


def _normalize_module(value: Any) -> str:
    module = _normalized_str(value, default="offline_transcribe").lower()
    if not module:
        module = "offline_transcribe"
    return module


def _default_downloads_dir() -> Path:
    return Path.home() / "Downloads"


@dataclass(slots=True)
class SourceSpec:
    mode: str = "local"
    path: str = ""
    files: list[str] = field(default_factory=list)
    url: str = ""
    is_playlist: bool = False
    quality: str = "best"

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "SourceSpec":
        mode = _normalized_str(payload.get("mode"), default="local").lower()
        if mode not in ALLOWED_SOURCE_MODES:
            raise RequestValidationError(
                "source.mode must be one of: local, youtube",
                details={"source.mode": mode},
            )

        files_raw = payload.get("files") or []
        files: list[str]
        if isinstance(files_raw, list):
            files = [str(item) for item in files_raw if str(item or "").strip()]
        else:
            raise RequestValidationError("source.files must be an array.", details={"source.files": files_raw})

        spec = cls(
            mode=mode,
            path=_normalized_str(payload.get("path")),
            files=files,
            url=_normalized_str(payload.get("url")),
            is_playlist=_as_bool(payload.get("is_playlist"), default=False),
            quality=_normalized_str(payload.get("quality"), default="best"),
        )

        if spec.mode == "local" and not spec.path and not spec.files:
            raise RequestValidationError(
                "Local source requires source.path or source.files.",
                details={"source": payload},
            )
        if spec.mode == "youtube" and not spec.url:
            raise RequestValidationError(
                "YouTube source requires source.url.",
                details={"source": payload},
            )

        return spec


@dataclass(slots=True)
class OutputSpec:
    mode: str = "txt_only"
    out_dir: str = ""
    output_prefix: str = ""
    keep_originals: bool = False

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "OutputSpec":
        mode = _normalized_str(payload.get("mode"), default="txt_only").lower()
        if mode not in ALLOWED_OUTPUT_MODES:
            raise RequestValidationError(
                "output.mode must be one of: conference, video_subs, video_dub, srt_only, txt_only",
                details={"output.mode": mode},
            )

        out_dir = _normalized_str(payload.get("out_dir"))
        if not out_dir:
            out_dir = str(_default_downloads_dir())

        return cls(
            mode=mode,
            out_dir=out_dir,
            output_prefix=_normalized_str(payload.get("output_prefix")),
            keep_originals=_as_bool(payload.get("keep_originals"), default=False),
        )


@dataclass(slots=True)
class TranscriptionSpec:
    model: str = "large-v3"
    auto_model: bool = False
    prefer_gpu: bool = True
    source_lang: str = "auto"
    batch_size: int = 16

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "TranscriptionSpec":
        return cls(
            model=_normalized_str(payload.get("model"), default="large-v3"),
            auto_model=_as_bool(payload.get("auto_model"), default=False),
            prefer_gpu=_as_bool(payload.get("prefer_gpu"), default=True),
            source_lang=_normalized_str(payload.get("source_lang"), default="auto"),
            batch_size=_as_int(payload.get("batch_size"), default=16, minimum=1, maximum=128),
        )


@dataclass(slots=True)
class TranslationSpec:
    enabled: bool = True
    target_lang: str = "en→cs"

    @property
    def model_name(self) -> str:
        return TRANSLATION_MODELS.get(self.target_lang, TRANSLATION_MODELS["en→cs"])

    @property
    def lang_suffix(self) -> str:
        if "→" in self.target_lang:
            return self.target_lang.split("→", 1)[1].strip() or "translated"
        return "translated"

    @property
    def lang_code(self) -> str:
        return LANG_CODES.get(self.target_lang, "eng")

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "TranslationSpec":
        target_lang = _normalize_target_lang(_normalized_str(payload.get("target_lang"), default="en→cs"))
        if target_lang not in TRANSLATION_MODELS:
            raise RequestValidationError(
                "translation.target_lang is not supported.",
                details={"target_lang": target_lang, "supported": sorted(TRANSLATION_MODELS.keys())},
            )
        return cls(
            enabled=_as_bool(payload.get("enabled"), default=True),
            target_lang=target_lang,
        )


@dataclass(slots=True)
class SubtitleStyleSpec:
    mode: str = "soft"
    font: str = "Arial"
    size: int = 24
    color: str = "#FFFFFF"
    outline_color: str = "#000000"
    outline_width: int = 2

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "SubtitleStyleSpec":
        mode = _normalized_str(payload.get("mode"), default="soft").lower()
        if mode not in ALLOWED_SUBTITLE_MODES:
            raise RequestValidationError(
                "subtitles.mode must be one of: soft, hard",
                details={"subtitles.mode": mode},
            )

        return cls(
            mode=mode,
            font=_normalized_str(payload.get("font"), default="Arial"),
            size=_as_int(payload.get("size"), default=24, minimum=8, maximum=96),
            color=_normalized_str(payload.get("color"), default="#FFFFFF"),
            outline_color=_normalized_str(payload.get("outline_color"), default="#000000"),
            outline_width=_as_int(payload.get("outline_width"), default=2, minimum=0, maximum=12),
        )


@dataclass(slots=True)
class TextExportSpec:
    clean_text: bool = False
    export_md: bool = False
    summary_pack: bool = False
    split_minutes: int = 0
    summary_lang: str = "auto"
    speaker: str = ""
    topic: str = ""

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "TextExportSpec":
        return cls(
            clean_text=_as_bool(payload.get("clean_text"), default=False),
            export_md=_as_bool(payload.get("export_md"), default=False),
            summary_pack=_as_bool(payload.get("summary_pack"), default=False),
            split_minutes=_as_int(payload.get("split_minutes"), default=0, minimum=0, maximum=720),
            summary_lang=_normalized_str(payload.get("summary_lang"), default="auto"),
            speaker=_normalized_str(payload.get("speaker")),
            topic=_normalized_str(payload.get("topic")),
        )


@dataclass(slots=True)
class ConferenceDefaultsSpec:
    speaker: str = ""
    topic: str = ""
    lecture_description: str = ""
    lecture_date: str = ""
    conference_title: str = ""
    conference_date: str = ""

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "ConferenceDefaultsSpec":
        return cls(
            speaker=_normalized_str(payload.get("speaker")),
            topic=_normalized_str(payload.get("topic")),
            lecture_description=_normalized_str(
                payload.get("lecture_description") or payload.get("description")
            ),
            lecture_date=_normalized_str(payload.get("lecture_date") or payload.get("date")),
            conference_title=_normalized_str(payload.get("conference_title")),
            conference_date=_normalized_str(payload.get("conference_date")),
        )


@dataclass(slots=True)
class DiarizationSpec:
    enabled: bool = False
    backend: str = "stable_local"
    min_speakers: int = 0
    max_speakers: int = 0
    include_unmapped_speakers: bool = True
    speaker_prefix_in_srt: bool = True
    review_after_file: bool = False
    profile_prefill: bool = True
    speaker_profiles: dict[str, str] = field(default_factory=dict)
    hf_token: str = ""
    fail_on_error: bool = False

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "DiarizationSpec":
        backend = _normalized_str(payload.get("backend"), default="stable_local").lower()
        if backend not in ALLOWED_DIARIZATION_BACKENDS:
            raise RequestValidationError(
                "diarization.backend must be one of: stable_local, advanced_pyannote",
                details={"diarization.backend": backend},
            )

        min_speakers = _as_int(payload.get("min_speakers"), default=0, minimum=0, maximum=32)
        max_speakers = _as_int(payload.get("max_speakers"), default=0, minimum=0, maximum=32)
        if min_speakers > 0 and max_speakers > 0 and max_speakers < min_speakers:
            max_speakers = min_speakers

        profiles_raw = payload.get("speaker_profiles") or {}
        speaker_profiles: dict[str, str] = {}
        if isinstance(profiles_raw, dict):
            for key, value in profiles_raw.items():
                k = _normalized_str(key).upper()
                v = _normalized_str(value)
                if k:
                    speaker_profiles[k] = v

        return cls(
            enabled=_as_bool(payload.get("enabled"), default=False),
            backend=backend,
            min_speakers=min_speakers,
            max_speakers=max_speakers,
            include_unmapped_speakers=_as_bool(payload.get("include_unmapped_speakers"), default=True),
            speaker_prefix_in_srt=_as_bool(payload.get("speaker_prefix_in_srt"), default=True),
            review_after_file=_as_bool(payload.get("review_after_file"), default=False),
            profile_prefill=_as_bool(payload.get("profile_prefill"), default=True),
            speaker_profiles=speaker_profiles,
            hf_token=_normalized_str(payload.get("hf_token")),
            fail_on_error=_as_bool(payload.get("fail_on_error"), default=False),
        )


@dataclass(slots=True)
class PipelineRequest:
    module: str
    source: SourceSpec
    output: OutputSpec
    transcription: TranscriptionSpec
    translation: TranslationSpec
    subtitles: SubtitleStyleSpec
    text_export: TextExportSpec
    diarization: DiarizationSpec
    conference_defaults: ConferenceDefaultsSpec = field(default_factory=ConferenceDefaultsSpec)
    conference_meta: dict[str, dict[str, str]] = field(default_factory=dict)

    @property
    def translation_needed(self) -> bool:
        return self.translation.enabled and self.output.mode in {"video_subs", "video_dub", "srt_only"}

    @property
    def needs_video_render(self) -> bool:
        return self.output.mode == "video_subs"

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "PipelineRequest":
        if not isinstance(payload, dict):
            raise RequestValidationError("run_pipeline params must be an object.")

        module = _normalize_module(payload.get("module"))
        if module not in ALLOWED_MODULES:
            raise RequestValidationError(
                "module is not supported.",
                details={"module": module, "supported": sorted(ALLOWED_MODULES)},
            )

        source = SourceSpec.from_payload(dict(payload.get("source") or {}))
        output = OutputSpec.from_payload(dict(payload.get("output") or {}))
        transcription = TranscriptionSpec.from_payload(dict(payload.get("transcription") or {}))
        translation = TranslationSpec.from_payload(dict(payload.get("translation") or {}))
        subtitles = SubtitleStyleSpec.from_payload(dict(payload.get("subtitles") or {}))
        text_export = TextExportSpec.from_payload(dict(payload.get("text") or {}))
        diarization = DiarizationSpec.from_payload(dict(payload.get("diarization") or {}))
        conference_defaults = ConferenceDefaultsSpec.from_payload(dict(payload.get("conference_defaults") or {}))

        conference_meta_raw = payload.get("conference_meta") or {}
        if not isinstance(conference_meta_raw, dict):
            raise RequestValidationError("conference_meta must be an object mapping file names to metadata.")

        conference_meta: dict[str, dict[str, str]] = {}
        for key, value in conference_meta_raw.items():
            if not isinstance(value, dict):
                continue
            conference_meta[str(key)] = {
                "speaker": _normalized_str(value.get("speaker")),
                "topic": _normalized_str(value.get("topic")),
                "lecture_description": _normalized_str(
                    value.get("lecture_description") or value.get("description")
                ),
                "lecture_date": _normalized_str(value.get("lecture_date") or value.get("date")),
                "conference_title": _normalized_str(value.get("conference_title")),
                "conference_date": _normalized_str(value.get("conference_date")),
            }

        if output.mode == "video_subs" and source.mode == "local":
            # Audio-only inputs fail later with a user-facing error. This keeps API explicit.
            pass
        if module == "youtube_dub" and source.mode != "youtube":
            raise RequestValidationError(
                "module 'youtube_dub' requires source.mode='youtube'.",
                details={"module": module, "source.mode": source.mode},
            )
        if module == "youtube_dub" and output.mode != "video_dub":
            raise RequestValidationError(
                "module 'youtube_dub' requires output.mode='video_dub'.",
                details={"module": module, "output.mode": output.mode},
            )
        if output.mode == "video_dub" and not translation.enabled:
            raise RequestValidationError(
                "output.mode 'video_dub' requires translation.enabled=true.",
                details={"output.mode": output.mode, "translation.enabled": translation.enabled},
            )

        return cls(
            module=module,
            source=source,
            output=output,
            transcription=transcription,
            translation=translation,
            subtitles=subtitles,
            text_export=text_export,
            diarization=diarization,
            conference_defaults=conference_defaults,
            conference_meta=conference_meta,
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "module": self.module,
            "source": {
                "mode": self.source.mode,
                "path": self.source.path,
                "files": list(self.source.files),
                "url": self.source.url,
                "is_playlist": self.source.is_playlist,
                "quality": self.source.quality,
            },
            "output": {
                "mode": self.output.mode,
                "out_dir": self.output.out_dir,
                "output_prefix": self.output.output_prefix,
                "keep_originals": self.output.keep_originals,
            },
            "transcription": {
                "model": self.transcription.model,
                "auto_model": self.transcription.auto_model,
                "prefer_gpu": self.transcription.prefer_gpu,
                "source_lang": self.transcription.source_lang,
                "batch_size": self.transcription.batch_size,
            },
            "translation": {
                "enabled": self.translation.enabled,
                "target_lang": self.translation.target_lang,
                "model_name": self.translation.model_name,
                "lang_suffix": self.translation.lang_suffix,
                "lang_code": self.translation.lang_code,
            },
            "subtitles": {
                "mode": self.subtitles.mode,
                "font": self.subtitles.font,
                "size": self.subtitles.size,
                "color": self.subtitles.color,
                "outline_color": self.subtitles.outline_color,
                "outline_width": self.subtitles.outline_width,
            },
            "text": {
                "clean_text": self.text_export.clean_text,
                "export_md": self.text_export.export_md,
                "summary_pack": self.text_export.summary_pack,
                "split_minutes": self.text_export.split_minutes,
                "summary_lang": self.text_export.summary_lang,
                "speaker": self.text_export.speaker,
                "topic": self.text_export.topic,
            },
            "diarization": {
                "enabled": self.diarization.enabled,
                "backend": self.diarization.backend,
                "min_speakers": self.diarization.min_speakers,
                "max_speakers": self.diarization.max_speakers,
                "include_unmapped_speakers": self.diarization.include_unmapped_speakers,
                "speaker_prefix_in_srt": self.diarization.speaker_prefix_in_srt,
                "review_after_file": self.diarization.review_after_file,
                "profile_prefill": self.diarization.profile_prefill,
                "speaker_profiles": dict(self.diarization.speaker_profiles),
                "fail_on_error": self.diarization.fail_on_error,
            },
            "conference_defaults": {
                "speaker": self.conference_defaults.speaker,
                "topic": self.conference_defaults.topic,
                "lecture_description": self.conference_defaults.lecture_description,
                "lecture_date": self.conference_defaults.lecture_date,
                "conference_title": self.conference_defaults.conference_title,
                "conference_date": self.conference_defaults.conference_date,
            },
            "conference_meta": dict(self.conference_meta),
        }


@dataclass(slots=True)
class PipelineArtifact:
    path: str
    kind: str
    label: str


@dataclass(slots=True)
class PipelineRunResult:
    output_root: str
    final_output_dir: str
    processed_items: int
    total_items: int
    artifacts: list[PipelineArtifact] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "output_root": self.output_root,
            "final_output_dir": self.final_output_dir,
            "processed_items": int(self.processed_items),
            "total_items": int(self.total_items),
            "artifacts": [
                {"path": item.path, "kind": item.kind, "label": item.label}
                for item in self.artifacts
            ],
            "warnings": list(self.warnings),
        }


def progress_payload(
    *,
    step: str,
    overall_pct: float,
    step_pct: float | None,
    item_index: int,
    total_items: int,
    indeterminate: bool = False,
) -> dict[str, Any]:
    return {
        "step": step,
        "overall_pct": round(_as_float(overall_pct, default=0.0), 2),
        "step_pct": None if step_pct is None else round(_as_float(step_pct, default=0.0), 2),
        "item_index": int(item_index),
        "total_items": int(total_items),
        "indeterminate": bool(indeterminate),
    }
