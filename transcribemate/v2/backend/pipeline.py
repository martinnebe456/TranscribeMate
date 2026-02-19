"""Pipeline orchestration for the V2 backend jobs."""

from __future__ import annotations

import shutil
import tempfile
from pathlib import Path
from threading import Event
from typing import Callable

from ...core.files import (
    cleanup_workdir,
    is_audio_file,
    list_videos,
    sanitize_filename,
    timestamped_base_name,
    unique_path,
)
from ...core.gpu import auto_whisper_model, get_gpu_info
from ...core.i18n import TRANSLATION_MODELS
from ...core.models import configure_model_environment
from ...core.paths import ensure_site_packages_on_path, ffmpeg_path
from ...core.transcripts import export_transcripts
from ...core.types import TranscriptionResult
from ...pipeline.diarize import (
    apply_speaker_mapping,
    build_speaker_sidecar,
    build_speakerized_srt,
    save_speaker_sidecar,
)
from ...pipeline.download import download_single_or_playlist
from ...pipeline.dubbing import dub_video_with_edge_tts
from ...pipeline.subtitles import hard_subtitles, soft_subtitles
from ...pipeline.transcribe import faster_whisper_transcribe
from ...pipeline.translate import translate_srt
from .diarization import run_diarization_backend
from .models import PipelineArtifact, PipelineRequest, PipelineRunResult, progress_payload

LogCallback = Callable[[str], None]
ProgressCallback = Callable[[dict], None]


class PipelineCancelledError(RuntimeError):
    """Raised when a running pipeline job is cancelled by user request."""


def _artifact_kind_for(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".txt":
        return "transcript_txt"
    if suffix == ".md":
        return "markdown"
    if suffix == ".srt":
        return "subtitle"
    if suffix == ".json":
        return "sidecar"
    if suffix == ".mp4":
        return "video"
    return "file"


class PipelineOrchestrator:
    """Runs one pipeline request in the backend process."""

    def __init__(
        self,
        *,
        request: PipelineRequest,
        stop_flag: Event,
        log_cb: LogCallback,
        progress_cb: ProgressCallback,
    ):
        self.request = request
        self.stop_flag = stop_flag
        self.log_cb = log_cb
        self.progress_cb = progress_cb

        self._total_items = 1
        self._current_item = 1
        self._step = "init"
        self._artifacts: list[PipelineArtifact] = []
        self._warnings: list[str] = []

    def run(self) -> PipelineRunResult:
        configure_model_environment()
        ensure_site_packages_on_path()

        out_root = Path(self.request.output.out_dir).expanduser().resolve()
        out_root.mkdir(parents=True, exist_ok=True)

        # Project-first layout: all generated artifacts go directly into output root.
        final_base_dir = out_root
        transcripts_dir = final_base_dir
        subtitles_src_final_dir = final_base_dir
        subtitles_trans_final_dir = final_base_dir
        videos_final_dir = final_base_dir
        summaries_dir = final_base_dir
        final_base_dir.mkdir(parents=True, exist_ok=True)

        workdir: Path | None = None
        completed = 0

        try:
            workdir = Path(tempfile.mkdtemp(prefix="_tm_work_", dir=out_root))
            downloads_dir = workdir / "downloads"
            srt_src_dir = workdir / "srt_source"
            srt_trans_dir = workdir / "srt_translated"
            for directory in (downloads_dir, srt_src_dir, srt_trans_dir):
                directory.mkdir(parents=True, exist_ok=True)

            self._log(f"[INFO] Workdir: {workdir}")
            self._log(f"[INFO] Outputs: {final_base_dir}")

            videos, _ = self._collect_inputs(downloads_dir)
            self._total_items = len(videos)
            if self._total_items == 0:
                raise RuntimeError("No media files were found.")

            if self.request.output.mode == "video_subs" and any(is_audio_file(video) for video in videos):
                raise RuntimeError(
                    "Output mode 'video_subs' does not support audio-only inputs. "
                    "Use 'txt_only' or 'srt_only'."
                )

            translation_needed = self.request.translation_needed
            translation_model = (
                TRANSLATION_MODELS[self.request.translation.target_lang]
                if translation_needed
                else ""
            )
            lang_suffix = self.request.translation.lang_suffix
            lang_code = self.request.translation.lang_code

            model = self._resolve_model_name()
            self._log(
                f"[INFO] Effective transcription model: {model} "
                f"| module={self.request.module} "
                f"| GPU requested={self.request.transcription.prefer_gpu}"
            )

            for index, media_path in enumerate(videos, start=1):
                self._check_cancelled()
                self._current_item = index

                self._log(f"[INFO] Processing {index}/{len(videos)}: {media_path.name}")
                base_name = timestamped_base_name(media_path, prefix=self.request.output.output_prefix)

                self._set_step("transcribe")
                transcription = faster_whisper_transcribe(
                    media_path,
                    srt_src_dir,
                    model,
                    self.request.transcription.prefer_gpu,
                    self.request.transcription.source_lang,
                    self._log_legacy,
                    self._set_step_progress,
                    self.stop_flag,
                )

                self._log(
                    "[INFO] Transcription done: "
                    f"lang={transcription.detected_lang}, duration={transcription.duration:.1f}s"
                )

                source_srt_for_pipeline = transcription.srt_path
                speaker = ""
                topic = ""
                lecture_description = ""
                lecture_date = ""
                conference_title = ""
                conference_date = ""
                if self.request.output.mode == "conference":
                    conference_item = self._conference_metadata_for(media_path)
                    speaker = str(conference_item.get("speaker", "") or "")
                    topic = str(conference_item.get("topic", "") or "")
                    lecture_description = str(conference_item.get("lecture_description", "") or "")
                    lecture_date = str(conference_item.get("lecture_date", "") or "")
                    conference_title = str(conference_item.get("conference_title", "") or "")
                    conference_date = str(conference_item.get("conference_date", "") or "")
                if not speaker:
                    speaker = self.request.text_export.speaker
                if not topic:
                    topic = self.request.text_export.topic

                sidecar_path: Path | None = None
                final_srt_src: Path | None = None
                final_srt_trans: Path | None = None

                if self.request.diarization.enabled:
                    self._set_step("diarization")
                    diarization = run_diarization_backend(
                        backend=self.request.diarization.backend,
                        accuracy_profile=self.request.diarization.accuracy_profile,
                        media_path=media_path,
                        segments=transcription.segments,
                        prefer_gpu=self.request.transcription.prefer_gpu,
                        min_speakers=self.request.diarization.min_speakers,
                        max_speakers=self.request.diarization.max_speakers,
                        stop_flag=self.stop_flag,
                        log=self._log_legacy,
                        set_step_progress=self._set_step_progress,
                    )
                    transcription.speaker_turns = diarization.speaker_turns
                    transcription.speaker_map = apply_speaker_mapping(transcription.segments, {})
                    if self.request.diarization.profile_prefill and self.request.diarization.speaker_profiles:
                        transcription.speaker_map = apply_speaker_mapping(
                            transcription.segments,
                            self.request.diarization.speaker_profiles,
                        )
                    if diarization.warnings:
                        self._warnings.extend(diarization.warnings)
                        for warning in diarization.warnings:
                            self._log(f"[WARN] {warning}")

                    if self.request.diarization.review_after_file:
                        msg = (
                            "Note: 'review_after_file' interactive speaker review is not "
                            "implemented yet; applying available speaker_profiles only."
                        )
                        self._warnings.append(msg)
                        self._log(f"[WARN] {msg}")

                    if self.request.diarization.speaker_prefix_in_srt:
                        speaker_srt_tmp = srt_src_dir / f"{sanitize_filename(media_path.stem)}.{base_name}.speaker.srt"
                        build_speakerized_srt(
                            transcription.segments,
                            speaker_srt_tmp,
                            include_unmapped_speakers=self.request.diarization.include_unmapped_speakers,
                        )
                        source_srt_for_pipeline = speaker_srt_tmp
                        self._log(f"[INFO] Speakerized source SRT prepared: {speaker_srt_tmp}")

                    sidecar_path = unique_path(transcripts_dir, f"{base_name}.diarization.json")

                self._set_step("export_transcripts")
                exported = export_transcripts(
                    media_path=media_path,
                    result=transcription,
                    transcripts_dir=transcripts_dir,
                    clean_text=self.request.text_export.clean_text,
                    export_md=self.request.text_export.export_md,
                    split_minutes=self.request.text_export.split_minutes,
                    generate_summary_pack=self.request.text_export.summary_pack,
                    summaries_dir=summaries_dir,
                    output_mode=self.request.output.mode,
                    model_name=model,
                    output_prefix=self.request.output.output_prefix,
                    log=self._log_legacy,
                    speaker=speaker,
                    topic=topic,
                    summary_lang=self.request.text_export.summary_lang,
                    base_name_override=base_name,
                    include_unmapped_speakers=self.request.diarization.include_unmapped_speakers,
                    lecture_description=lecture_description,
                    lecture_date=lecture_date,
                    conference_title=conference_title,
                    conference_date=conference_date,
                )
                for path in exported:
                    self._add_artifact(path, label="Transcript export")

                if self.request.diarization.enabled and sidecar_path:
                    sidecar_payload = build_speaker_sidecar(
                        media_path=media_path,
                        base_name=base_name,
                        result=transcription,
                        model_name=model,
                        output_mode=self.request.output.mode,
                        output_prefix=self.request.output.output_prefix,
                        clean_text=self.request.text_export.clean_text,
                        export_md=self.request.text_export.export_md,
                        split_minutes=self.request.text_export.split_minutes,
                        generate_summary_pack=self.request.text_export.summary_pack,
                        summary_lang=self.request.text_export.summary_lang,
                        speaker=speaker,
                        topic=topic,
                        lecture_description=lecture_description,
                        lecture_date=lecture_date,
                        conference_title=conference_title,
                        conference_date=conference_date,
                        include_unmapped_speakers=self.request.diarization.include_unmapped_speakers,
                        speaker_prefix_in_srt=self.request.diarization.speaker_prefix_in_srt,
                        transcripts_dir=transcripts_dir,
                        summaries_dir=summaries_dir,
                    )
                    save_speaker_sidecar(sidecar_path, sidecar_payload)
                    self._add_artifact(sidecar_path, label="Diarization sidecar")
                    self._log(f"[OK] Speaker sidecar saved: {sidecar_path}")

                if translation_needed:
                    self._set_step("translate")
                    translated_tmp = srt_trans_dir / f"{sanitize_filename(media_path.stem)}.{lang_suffix}.srt"
                    translate_srt(
                        source_srt_for_pipeline,
                        translated_tmp,
                        translation_model,
                        self.request.transcription.prefer_gpu,
                        self.request.transcription.batch_size,
                        self._log_legacy,
                        self._set_step_progress,
                        self.stop_flag,
                    )

                    self._set_step("export_srt")
                    final_srt_src = unique_path(
                        subtitles_src_final_dir,
                        f"{base_name}.{transcription.detected_lang}.srt",
                    )
                    final_srt_trans = unique_path(
                        subtitles_trans_final_dir,
                        f"{base_name}.{lang_suffix}.srt",
                    )
                    shutil.copy2(source_srt_for_pipeline, final_srt_src)
                    shutil.copy2(translated_tmp, final_srt_trans)
                    self._add_artifact(final_srt_src, label="Source SRT")
                    self._add_artifact(final_srt_trans, label="Translated SRT")
                    self._log(f"[OK] Saved: {final_srt_src}")
                    self._log(f"[OK] Saved: {final_srt_trans}")

                    if self.request.diarization.enabled and sidecar_path:
                        sidecar_payload = build_speaker_sidecar(
                            media_path=media_path,
                            base_name=base_name,
                            result=transcription,
                            model_name=model,
                            output_mode=self.request.output.mode,
                            output_prefix=self.request.output.output_prefix,
                            clean_text=self.request.text_export.clean_text,
                            export_md=self.request.text_export.export_md,
                            split_minutes=self.request.text_export.split_minutes,
                            generate_summary_pack=self.request.text_export.summary_pack,
                            summary_lang=self.request.text_export.summary_lang,
                            speaker=speaker,
                            topic=topic,
                            lecture_description=lecture_description,
                            lecture_date=lecture_date,
                            conference_title=conference_title,
                            conference_date=conference_date,
                            include_unmapped_speakers=self.request.diarization.include_unmapped_speakers,
                            speaker_prefix_in_srt=self.request.diarization.speaker_prefix_in_srt,
                            transcripts_dir=transcripts_dir,
                            summaries_dir=summaries_dir,
                            srt_source_path=final_srt_src,
                            srt_translated_path=final_srt_trans,
                        )
                        save_speaker_sidecar(sidecar_path, sidecar_payload)

                    if self.request.output.mode == "video_dub":
                        self._set_step("dub_audio")
                        out_mp4 = unique_path(videos_final_dir, f"{base_name}.dub.{lang_suffix}.mp4")
                        dub_video_with_edge_tts(
                            video_path=media_path,
                            translated_srt=translated_tmp,
                            out_mp4=out_mp4,
                            target_lang=lang_suffix,
                            log=self._log_legacy,
                            set_step_progress=self._set_step_progress,
                            set_step_indeterminate=self._set_step_indeterminate,
                            stop_flag=self.stop_flag,
                        )
                        self._add_artifact(out_mp4, label="Dubbed video")
                    elif self.request.needs_video_render:
                        if not ffmpeg_path():
                            raise RuntimeError("ffmpeg not found. Install ffmpeg before using output mode 'video_subs'.")
                        self._set_step("embed_subtitles")
                        suffix = ".soft" if self.request.subtitles.mode == "soft" else ".hard"
                        out_mp4 = unique_path(videos_final_dir, f"{base_name}{suffix}.sub.mp4")
                        if self.request.subtitles.mode == "soft":
                            soft_subtitles(
                                media_path,
                                translated_tmp,
                                out_mp4,
                                lang_code,
                                self._log_legacy,
                                self._set_step_indeterminate,
                                self.stop_flag,
                            )
                        else:
                            hard_subtitles(
                                media_path,
                                translated_tmp,
                                out_mp4,
                                self.request.subtitles.font,
                                self.request.subtitles.size,
                                self.request.subtitles.color,
                                self.request.subtitles.outline_color,
                                self.request.subtitles.outline_width,
                                self._log_legacy,
                                self._set_step_indeterminate,
                                self.stop_flag,
                            )
                        self._add_artifact(out_mp4, label="Subtitled video")
                else:
                    if self.request.output.mode == "srt_only":
                        self._set_step("export_srt")
                        final_srt_src = unique_path(
                            subtitles_src_final_dir,
                            f"{base_name}.{transcription.detected_lang}.srt",
                        )
                        shutil.copy2(source_srt_for_pipeline, final_srt_src)
                        self._add_artifact(final_srt_src, label="Source SRT")
                        self._log(f"[OK] Saved: {final_srt_src}")
                        if self.request.diarization.enabled and sidecar_path:
                            sidecar_payload = build_speaker_sidecar(
                                media_path=media_path,
                                base_name=base_name,
                                result=transcription,
                                model_name=model,
                                output_mode=self.request.output.mode,
                                output_prefix=self.request.output.output_prefix,
                                clean_text=self.request.text_export.clean_text,
                                export_md=self.request.text_export.export_md,
                                split_minutes=self.request.text_export.split_minutes,
                                generate_summary_pack=self.request.text_export.summary_pack,
                                summary_lang=self.request.text_export.summary_lang,
                                speaker=speaker,
                                topic=topic,
                                lecture_description=lecture_description,
                                lecture_date=lecture_date,
                                conference_title=conference_title,
                                conference_date=conference_date,
                                include_unmapped_speakers=self.request.diarization.include_unmapped_speakers,
                                speaker_prefix_in_srt=self.request.diarization.speaker_prefix_in_srt,
                                transcripts_dir=transcripts_dir,
                                summaries_dir=summaries_dir,
                                srt_source_path=final_srt_src,
                                srt_translated_path=None,
                            )
                            save_speaker_sidecar(sidecar_path, sidecar_payload)

                    if self.request.needs_video_render:
                        if not ffmpeg_path():
                            raise RuntimeError("ffmpeg not found. Install ffmpeg before using output mode 'video_subs'.")

                        self._set_step("export_srt")
                        final_srt_src = unique_path(
                            subtitles_src_final_dir,
                            f"{base_name}.{transcription.detected_lang}.srt",
                        )
                        shutil.copy2(source_srt_for_pipeline, final_srt_src)
                        self._add_artifact(final_srt_src, label="Source SRT")
                        self._log(f"[OK] Saved: {final_srt_src}")

                        self._set_step("embed_subtitles")
                        suffix = ".soft" if self.request.subtitles.mode == "soft" else ".hard"
                        out_mp4 = unique_path(videos_final_dir, f"{base_name}{suffix}.sub.mp4")
                        embed_lang_code = self._language_code_for_srt(transcription.detected_lang)
                        if self.request.subtitles.mode == "soft":
                            soft_subtitles(
                                media_path,
                                source_srt_for_pipeline,
                                out_mp4,
                                embed_lang_code,
                                self._log_legacy,
                                self._set_step_indeterminate,
                                self.stop_flag,
                            )
                        else:
                            hard_subtitles(
                                media_path,
                                source_srt_for_pipeline,
                                out_mp4,
                                self.request.subtitles.font,
                                self.request.subtitles.size,
                                self.request.subtitles.color,
                                self.request.subtitles.outline_color,
                                self.request.subtitles.outline_width,
                                self._log_legacy,
                                self._set_step_indeterminate,
                                self.stop_flag,
                            )
                        self._add_artifact(out_mp4, label="Subtitled video")

                        if self.request.diarization.enabled and sidecar_path:
                            sidecar_payload = build_speaker_sidecar(
                                media_path=media_path,
                                base_name=base_name,
                                result=transcription,
                                model_name=model,
                                output_mode=self.request.output.mode,
                                output_prefix=self.request.output.output_prefix,
                                clean_text=self.request.text_export.clean_text,
                                export_md=self.request.text_export.export_md,
                                split_minutes=self.request.text_export.split_minutes,
                                generate_summary_pack=self.request.text_export.summary_pack,
                                summary_lang=self.request.text_export.summary_lang,
                                speaker=speaker,
                                topic=topic,
                                lecture_description=lecture_description,
                                lecture_date=lecture_date,
                                conference_title=conference_title,
                                conference_date=conference_date,
                                include_unmapped_speakers=self.request.diarization.include_unmapped_speakers,
                                speaker_prefix_in_srt=self.request.diarization.speaker_prefix_in_srt,
                                transcripts_dir=transcripts_dir,
                                summaries_dir=summaries_dir,
                                srt_source_path=final_srt_src,
                                srt_translated_path=None,
                            )
                            save_speaker_sidecar(sidecar_path, sidecar_payload)

                completed += 1
                self._emit_progress(step="item_done", step_pct=100.0, indeterminate=False)

            self._set_step("done")
            self._emit_progress(step="done", step_pct=100.0, indeterminate=False)

            return PipelineRunResult(
                output_root=str(out_root),
                final_output_dir=str(final_base_dir),
                processed_items=completed,
                total_items=self._total_items,
                artifacts=list(self._artifacts),
                warnings=list(self._warnings),
            )
        finally:
            cleanup_workdir(workdir, self._log_legacy)

    def _collect_inputs(self, downloads_dir: Path) -> tuple[list[Path], list[Path]]:
        self._set_step("collect_inputs")

        if self.request.source.mode == "youtube":
            self._emit_progress(step="download", step_pct=0.0, indeterminate=False)
            out_dir = download_single_or_playlist(
                self.request.source.url,
                downloads_dir.parent,
                self.request.source.is_playlist,
                self.request.source.quality,
                self._log_legacy,
                self._set_step_progress,
                self.stop_flag,
            )
            videos = list_videos(out_dir)
            self._log(f"[INFO] Downloaded media files: {len(videos)}")

            project_input_raw = str(self.request.project.input_dir or "").strip()
            if not project_input_raw:
                return videos, list(videos)

            project_input_dir = Path(project_input_raw).expanduser().resolve()
            project_input_dir.mkdir(parents=True, exist_ok=True)
            persisted: list[Path] = []
            for source in videos:
                target = unique_path(project_input_dir, source.name)
                shutil.copy2(source, target)
                persisted.append(target)
            self._log(
                "[INFO] Downloaded media persisted into project input: "
                f"{project_input_dir} ({len(persisted)} file(s))"
            )
            return persisted, list(persisted)

        if self.request.source.files:
            videos = [Path(path).expanduser() for path in self.request.source.files]
            missing = [str(path) for path in videos if not path.exists()]
            if missing:
                raise RuntimeError(f"Missing local files: {', '.join(missing[:5])}")
            return sorted(videos), []

        source_path = Path(self.request.source.path).expanduser()
        if not source_path.exists():
            raise RuntimeError(f"Input path does not exist: {source_path}")
        if source_path.is_file():
            return [source_path], []
        return list_videos(source_path), []

    def _resolve_model_name(self) -> str:
        if not self.request.transcription.auto_model:
            return self.request.transcription.model

        if not self.request.transcription.prefer_gpu:
            self._log("[INFO] Auto model selected without GPU preference -> small")
            return "small"

        info = get_gpu_info()
        if info.available:
            model = auto_whisper_model(info.vram_gb)
            self._log(f"[INFO] GPU detected: {info.name} ({info.vram_gb:.1f} GB). Auto model -> {model}")
            return model

        self._log("[WARN] GPU not available. Auto model -> small")
        return "small"

    def _conference_metadata_for(self, media_path: Path) -> dict[str, str]:
        metadata = {
            "speaker": self.request.conference_defaults.speaker,
            "topic": self.request.conference_defaults.topic,
            "lecture_description": self.request.conference_defaults.lecture_description,
            "lecture_date": self.request.conference_defaults.lecture_date,
            "conference_title": self.request.conference_defaults.conference_title,
            "conference_date": self.request.conference_defaults.conference_date,
        }

        if not self.request.conference_meta:
            return metadata

        keys = [
            str(media_path),
            str(media_path.resolve()),
            media_path.name,
            media_path.stem,
        ]
        for key in keys:
            if key in self.request.conference_meta:
                item = self.request.conference_meta[key]
                for field_name in metadata:
                    value = str(item.get(field_name, "") or "").strip()
                    if value:
                        metadata[field_name] = value
                return metadata
        return metadata

    @staticmethod
    def _language_code_for_srt(detected_lang: str) -> str:
        lang = str(detected_lang or "").strip().lower()
        if not lang:
            return "und"
        map_iso = {
            "en": "eng",
            "cs": "cze",
            "sk": "slk",
            "de": "deu",
            "pl": "pol",
            "fr": "fra",
            "es": "spa",
            "it": "ita",
            "ru": "rus",
            "uk": "ukr",
            "pt": "por",
            "ja": "jpn",
            "ko": "kor",
            "zh": "zho",
        }
        if lang in map_iso:
            return map_iso[lang]
        if len(lang) == 3:
            return lang
        return "und"

    def _check_cancelled(self):
        if self.stop_flag.is_set():
            raise PipelineCancelledError("Pipeline was cancelled.")

    def _add_artifact(self, path: Path, *, label: str):
        self._artifacts.append(
            PipelineArtifact(
                path=str(path),
                kind=_artifact_kind_for(path),
                label=label,
            )
        )

    def _set_step(self, step: str):
        self._step = step
        self._emit_progress(step=step, step_pct=0.0, indeterminate=False)

    def _set_step_progress(self, value: float):
        self._emit_progress(step=self._step, step_pct=float(value), indeterminate=False)

    def _set_step_indeterminate(self, value: bool):
        self._emit_progress(step=self._step, step_pct=None, indeterminate=bool(value))

    def _emit_progress(self, *, step: str, step_pct: float | None, indeterminate: bool):
        if self._total_items <= 0:
            overall = 0.0
        else:
            step_component = 0.0 if step_pct is None else max(0.0, min(100.0, float(step_pct))) / 100.0
            overall = ((self._current_item - 1) + step_component) / float(self._total_items) * 100.0

        self.progress_cb(
            progress_payload(
                step=step,
                overall_pct=overall,
                step_pct=step_pct,
                item_index=self._current_item,
                total_items=self._total_items,
                indeterminate=indeterminate,
            )
        )

    def _log(self, line: str):
        cleaned = str(line or "").rstrip("\n")
        if not cleaned:
            return
        self.log_cb(cleaned)

    def _log_legacy(self, line: str):
        text = str(line or "")
        for part in text.splitlines():
            self._log(part)
