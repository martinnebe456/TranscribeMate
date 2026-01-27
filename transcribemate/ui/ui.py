"""Tkinter UI for TranscribeMate."""

from __future__ import annotations

import json
import logging
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import time
import threading
from pathlib import Path
from typing import Dict, List, Optional, Sequence

import tkinter as tk
from tkinter import colorchooser, filedialog

import ttkbootstrap as tb
from tkinterdnd2 import DND_FILES, TkinterDnD
from ttkbootstrap.constants import *
from ttkbootstrap.dialogs import Messagebox

from ..core.config import AppConfig, load_config, save_config
from ..pipeline.download import download_single_or_playlist
from ..core.files import (
    cleanup_workdir,
    copy_originals_to_final,
    is_audio_file,
    list_videos,
    sanitize_filename,
    timestamped_base_name,
    unique_path,
)
from ..core.gpu import GpuInfo, auto_whisper_model, get_gpu_info
from ..core.i18n import (
    APP_NAME,
    AUDIO_EXTS,
    I18N,
    LANGUAGES,
    LANG_CODES,
    OUTPUT_MODE_KEYS,
    QUICK_MODEL_KEYS,
    QUICK_MODEL_MAP,
    SOURCE_LANGUAGES,
    TRANSLATION_MODELS,
    VIDEO_EXTS,
    VIDEO_QUALITIES,
    normalize_output_mode,
    normalize_quick_model,
    normalize_summary_lang,
    normalize_theme,
    output_mode_label_to_key,
    output_mode_labels,
    quick_model_key_for_model,
    quick_model_label_to_key,
    quick_model_labels,
    summary_lang_label_for_key,
    summary_lang_label_to_key,
    summary_lang_labels,
    theme_label_to_key,
    theme_labels,
    theme_name_for_key,
)
from ..core.models import force_refresh_models
from ..core.paths import ensure_tools, user_data_dir
from ..pipeline.subtitles import hard_subtitles, soft_subtitles
from ..pipeline.transcribe import faster_whisper_transcribe
from ..core.transcripts import export_transcripts
from ..pipeline.translate import translate_srt

# Keep Hugging Face cache compatible on Windows.
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS", "1")

_YT_URL_PATTERN = re.compile(
    r"^(https?://)?(www\.)?(youtube\.com|youtu\.be|youtube-nocookie\.com)/.+"
)

LOGGER = logging.getLogger(__name__)


def show_notification(title: str, message: str):
    """Best-effort desktop notification without crashing the UI loop."""
    try:
        from win10toast import ToastNotifier  # type: ignore

        toaster = ToastNotifier()
        toaster.show_toast(title, message, duration=6, threaded=True)
    except Exception:
        # Notifications are optional; never let them break the UI queue.
        LOGGER.debug("Notification failed", exc_info=True)
        return

# -----------------------------
# Custom Window with DnD support
# -----------------------------
class DnDWindow(TkinterDnD.Tk):
    """TkinterDnD root with ttkbootstrap styling support."""
    def __init__(self, *args, **kwargs):
        try:
            super().__init__(*args, **kwargs)
            self._dnd_enabled = True
        except Exception:
            self._dnd_enabled = False
            tk.Tk.__init__(self, *args, **kwargs)

    def drop_target_register(self, *args):
        if self._dnd_enabled:
            try:
                return self.tk.call('tkdnd::drop_target', 'register', self._w, *args)
            except Exception:
                LOGGER.debug("DnD drop_target_register failed", exc_info=True)

    def dnd_bind(self, sequence, func):
        if self._dnd_enabled:
            try:
                self.tk.call('tkdnd::bind', self._w, sequence, func)
            except Exception:
                LOGGER.debug("DnD bind failed", exc_info=True)


# -----------------------------
# Minimalist modern GUI
# -----------------------------
class App(DnDWindow):
    def __init__(self):
        self.cfg = load_config()
        initial_theme_key = normalize_theme(getattr(self.cfg, "theme", "light"))
        super().__init__()
        self.style = tb.Style(theme=theme_name_for_key(initial_theme_key))
        self.title(I18N["en"]["app.title"])
        self.geometry("1050x820")
        self.minsize(1000, 740)

        self.stop_flag = threading.Event()
        self.worker = None
        self.uiq = queue.Queue()

        # State variables
        self.source_mode = tb.StringVar(value="youtube")
        self.yt_mode = tb.StringVar(value="playlist")
        self.url = tb.StringVar()
        self.local_path = tb.StringVar()
        self.out_dir = tb.StringVar(value=self.cfg.out_dir)
        self.lang = tb.StringVar(value=self.cfg.lang)
        self.theme_key = tb.StringVar(value=initial_theme_key)
        self.theme_label = tb.StringVar()
        self.use_gpu = tb.BooleanVar(value=self.cfg.use_gpu)
        self.auto_model = tb.BooleanVar(value=self.cfg.auto_model)
        self.model = tb.StringVar(value=self.cfg.whisper_model)
        self.quick_model_key = tb.StringVar(value=normalize_quick_model(self.cfg.quick_model))
        self.quick_model = tb.StringVar()
        self.source_lang = tb.StringVar(value=self.cfg.source_lang)
        self.target_lang = tb.StringVar(value=self.cfg.target_lang)
        self.summary_lang_key = tb.StringVar(value=normalize_summary_lang(getattr(self.cfg, "summary_lang", "auto")))
        self.summary_lang_label = tb.StringVar()
        self.batch = tb.IntVar(value=self.cfg.batch_size)
        self.video_quality = tb.StringVar(value=self.cfg.video_quality)
        self.output_mode_key = tb.StringVar(value=normalize_output_mode(self.cfg.output_mode))
        self.output_mode = tb.StringVar()
        self.clean_text = tb.BooleanVar(value=self.cfg.clean_text)
        self.export_md = tb.BooleanVar(value=self.cfg.export_md)
        self.summary_pack = tb.BooleanVar(value=self.cfg.summary_pack)
        self.split_minutes = tb.IntVar(value=self.cfg.split_minutes)
        self.keep_originals = tb.BooleanVar(value=self.cfg.keep_originals)
        self.output_prefix = tb.StringVar(value=getattr(self.cfg, "output_prefix", ""))
        self.notify_on_done = tb.BooleanVar(value=getattr(self.cfg, "notify_on_done", False))

        self.subtitle_mode = tb.StringVar(value=self.cfg.subtitle_mode)
        self.show_log = tb.BooleanVar(value=True)

        self._local_files: List[Path] = []
        self._local_list_items: List[Path] = []
        self._conf_meta: Dict[str, Dict[str, str]] = {}
        self._conf_meta_path: Optional[Path] = None
        self._conf_selected_path: Optional[Path] = None

        if not self.auto_model.get() and self.model.get() in QUICK_MODEL_MAP.values():
            self.quick_model_key.set(quick_model_key_for_model(self.model.get()))

        # Subtitle style
        self.sub_font = tb.StringVar(value=self.cfg.sub_font)
        self.sub_size = tb.IntVar(value=self.cfg.sub_size)
        self.sub_color = tb.StringVar(value=self.cfg.sub_color)
        self.sub_outline_color = tb.StringVar(value=self.cfg.sub_outline_color)
        self.sub_outline_width = tb.IntVar(value=self.cfg.sub_outline_width)

        self.conf_speaker = tb.StringVar()

        self.total_videos = 0
        self.done_videos = 0
        self.final_base_dir = Path(self.out_dir.get()) / "transcribemate_outputs"

        self._gpu_info_cache: Optional[GpuInfo] = None
        self._gpu_check_thread: Optional[threading.Thread] = None
        self._gpu_usage_cache: Optional[float] = None
        self._gpu_mem_cache: Optional[tuple[float, float]] = None
        self._gpu_poll_inflight = False
        self._gpu_smi_available = shutil.which("nvidia-smi") is not None
        self._step_started_at: Optional[float] = None

        self._build_ui()
        self._setup_drag_drop()
        self.after(100, self._drain_uiq)
        self._update_source_ui()
        self._update_subtitle_options()
        self.after(0, self._maximize_window)
        self.after(200, self._start_system_monitor)

        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _setup_drag_drop(self):
        """Setup drag and drop for URL entry and local path"""
        try:
            # Register the window for drag and drop
            self.drop_target_register(DND_FILES)
            self.dnd_bind('<<Drop>>', self._on_drop)
        except Exception:
            # DnD not available
            LOGGER.debug("DnD setup failed", exc_info=True)

    def _on_drop(self, event):
        """Handle dropped files/URLs"""
        data = event.data if hasattr(event, 'data') else str(event)
        if not data:
            return

        cleaned = data.strip()
        if _YT_URL_PATTERN.match(cleaned):
            self.source_mode.set("youtube")
            self.url.set(cleaned)
            self._local_files = []
            self._update_local_list([])
            self._update_source_ui()
            return

        paths = self._parse_dnd_paths(cleaned)
        if not paths:
            return

        path_objs = [Path(p) for p in paths if p]
        existing = [p for p in path_objs if p.exists()]
        if not existing:
            return

        self.source_mode.set("local")
        if len(existing) == 1 and existing[0].is_dir():
            self._set_local_path(existing[0])
        else:
            files = [p for p in existing if p.is_file()]
            if files:
                self._set_local_files(files)
            else:
                first_dir = next((p for p in existing if p.is_dir()), None)
                if first_dir:
                    self._set_local_path(first_dir)
        self._update_source_ui()

    def _on_close(self):
        self._save_conference_meta_from_ui()
        self._save_current_config()
        self.destroy()

    def _save_current_config(self):
        self.cfg.out_dir = self.out_dir.get()
        self.cfg.lang = self.lang.get()
        self.cfg.theme = normalize_theme(self.theme_key.get())
        self.cfg.use_gpu = self.use_gpu.get()
        self.cfg.auto_model = self.auto_model.get()
        self.cfg.whisper_model = self.model.get()
        self.cfg.quick_model = normalize_quick_model(self.quick_model_key.get())
        self.cfg.source_lang = self.source_lang.get()
        self.cfg.target_lang = self.target_lang.get()
        self.cfg.summary_lang = normalize_summary_lang(self.summary_lang_key.get())
        self.cfg.batch_size = self.batch.get()
        self.cfg.subtitle_mode = self.subtitle_mode.get()
        self.cfg.video_quality = self.video_quality.get()
        self.cfg.output_mode = normalize_output_mode(self.output_mode_key.get())
        self.cfg.clean_text = bool(self.clean_text.get())
        self.cfg.export_md = bool(self.export_md.get())
        self.cfg.summary_pack = bool(self.summary_pack.get())
        try:
            self.cfg.split_minutes = max(0, int(self.split_minutes.get()))
        except Exception:
            self.cfg.split_minutes = 0
        self.cfg.keep_originals = bool(self.keep_originals.get())
        self.cfg.output_prefix = self.output_prefix.get().strip()
        self.cfg.notify_on_done = bool(self.notify_on_done.get())
        self.cfg.sub_font = self.sub_font.get()
        self.cfg.sub_size = self.sub_size.get()
        self.cfg.sub_color = self.sub_color.get()
        self.cfg.sub_outline_color = self.sub_outline_color.get()
        self.cfg.sub_outline_width = self.sub_outline_width.get()
        save_config(self.cfg)

    def t(self, key: str) -> str:
        lang = self.lang.get() if self.lang.get() in LANGUAGES else "en"
        table = I18N.get(lang, I18N["en"])
        if key in table:
            return table[key]
        return I18N["en"].get(key, key)

    # -------- UI queue helpers --------
    def q_log(self, s: str):
        self.uiq.put(("log", s))

    def q_status(self, s: str):
        self.uiq.put(("status", s))

    def q_step(self, s: str):
        self.uiq.put(("step", s))

    def q_step_progress(self, pct: float):
        self.uiq.put(("step_progress", pct))

    def q_step_indeterminate(self, on: bool):
        self.uiq.put(("step_indeterminate", on))

    def q_overall(self, done: int, total: int):
        self.uiq.put(("overall", done, total))

    def q_buttons_reset(self):
        self.uiq.put(("buttons_reset",))

    def q_notify(self, title: str, message: str):
        self.uiq.put(("notify", title, message))

    def q_gpu_info(self, gi: GpuInfo):
        self.uiq.put(("gpu_info", gi))

    def _show_error_async(self, msg: str):
        self.after(0, lambda: Messagebox.show_error(self.t("dialog.error"), msg))

    def _drain_uiq(self):
        try:
            while True:
                item = self.uiq.get_nowait()
                kind = item[0]
                try:
                    if kind == "log":
                        self.log_txt.insert("end", item[1])
                        if self.show_log.get():
                            self.log_txt.see("end")
                    elif kind == "status":
                        self.status_lbl.configure(text=item[1])
                    elif kind == "step":
                        self._current_step_text = str(item[1])
                        self.step_lbl.configure(text=self._current_step_text)
                        self.step_bar.stop()
                        self.step_bar.configure(mode="determinate")
                        self.step_bar["value"] = 0
                        self._step_started_at = time.monotonic()
                        self.eta_lbl.configure(text="—")
                    elif kind == "step_progress":
                        v = max(0.0, min(100.0, float(item[1])))
                        if str(self.step_bar["mode"]) != "determinate":
                            self.step_bar.stop()
                            self.step_bar.configure(mode="determinate")
                        self.step_bar["value"] = v
                        if self._current_step_text:
                            self.step_lbl.configure(text=f"{self._current_step_text} — {v:.0f}%")
                        eta_text = self._estimate_eta_text(v)
                        if eta_text:
                            self.eta_lbl.configure(text=eta_text)
                    elif kind == "step_indeterminate":
                        on = bool(item[1])
                        if on:
                            self.step_lbl.configure(text=self._current_step_text)
                            self.step_bar["value"] = 0
                            self.step_bar.configure(mode="indeterminate")
                            self.step_bar.start(12)
                            self.eta_lbl.configure(text="—")
                        else:
                            self.step_bar.stop()
                            self.step_bar.configure(mode="determinate")
                            self.step_bar["value"] = 100
                            self.step_lbl.configure(text=self._current_step_text)
                            self.eta_lbl.configure(text=self._estimate_eta_text(100.0) or "—")
                    elif kind == "overall":
                        done, total = int(item[1]), int(item[2])
                        self.overall_bar["maximum"] = max(1, total)
                        self.overall_bar["value"] = done
                        self.overall_lbl.configure(text=f"{done}/{total}")
                    elif kind == "buttons_reset":
                        # Ensure the UI can start another run even if a notification fails.
                        self.worker = None
                        self.stop_flag.clear()
                        self.start_btn.configure(state="normal")
                        self.stop_btn.configure(state="disabled")
                    elif kind == "gpu_info":
                        self._apply_gpu_info(item[1])
                    elif kind == "notify":
                        show_notification(item[1], item[2])
                except Exception as exc:
                    # Never let a single UI event break the queue pump.
                    try:
                        self.log_txt.insert("end", f"[WARN] UI queue error: {exc}\n")
                        if self.show_log.get():
                            self.log_txt.see("end")
                    except Exception:
                        pass
        except queue.Empty:
            pass
        finally:
            self.after(100, self._drain_uiq)

    def _apply_style_overrides(self):
        self.style.configure("TLabel", font=("Segoe UI", 10))
        self.style.configure("Title.TLabel", font=("Segoe UI", 14, "bold"))

    def _apply_theme(self, *, save: bool = False):
        key = normalize_theme(self.theme_key.get())
        self.theme_key.set(key)
        theme_name = theme_name_for_key(key)
        try:
            self.style.theme_use(theme_name)
        except Exception:
            LOGGER.debug("Theme apply failed, falling back to light", exc_info=True)
            self.theme_key.set("light")
            self.style.theme_use(theme_name_for_key("light"))

        table = I18N.get(self.lang.get(), I18N["en"])
        self.theme_label.set(table.get(f"theme.{self.theme_key.get()}", self.theme_key.get()))
        self._apply_style_overrides()
        if save:
            self._save_current_config()

    def _on_theme_changed(self):
        selected_label = self.theme_label.get()
        new_key = theme_label_to_key(selected_label)
        self.theme_key.set(new_key)
        self._apply_theme(save=True)


    # -------- UI --------
    def _build_ui(self):
        self._apply_style_overrides()

        root = tb.Frame(self, padding=16)
        root.pack(fill="both", expand=True)

        # Header
        header = tb.Frame(root)
        header.pack(fill="x")
        self.header_title_lbl = tb.Label(header, text="", style="Title.TLabel")
        self.header_title_lbl.pack(side="left")
        lang_row = tb.Frame(header)
        lang_row.pack(side="right", padx=(10, 0))
        self.lang_lbl = tb.Label(lang_row, text="")
        self.lang_lbl.pack(side="left", padx=(0, 6))
        self.lang_combo = tb.Combobox(lang_row, textvariable=self.lang, values=LANGUAGES, state="readonly", width=5)
        self.lang_combo.pack(side="left")
        self.lang_combo.bind("<<ComboboxSelected>>", lambda _e: self._apply_translations())

        self.theme_lbl = tb.Label(lang_row, text="")
        self.theme_lbl.pack(side="left", padx=(12, 6))
        self.theme_combo = tb.Combobox(
            lang_row,
            textvariable=self.theme_label,
            values=[],
            state="readonly",
            width=10,
        )
        self.theme_combo.pack(side="left")
        self.theme_combo.bind("<<ComboboxSelected>>", lambda _e: self._on_theme_changed())

        self.gpu_badge = tb.Label(header, text="", bootstyle="secondary")
        self.gpu_badge.pack(side="right")

        tb.Separator(root).pack(fill="x", pady=10)

        # Source selection
        self.source_frame = tb.Labelframe(root, text="", padding=10)
        self.source_frame.pack(fill="x")

        source_row = tb.Frame(self.source_frame)
        source_row.pack(fill="x")
        self.youtube_rb = tb.Radiobutton(source_row, text="", value="youtube", variable=self.source_mode,
                                         command=self._update_source_ui, bootstyle="primary-toolbutton")
        self.youtube_rb.pack(side="left")
        self.local_rb = tb.Radiobutton(source_row, text="", value="local", variable=self.source_mode,
                                       command=self._update_source_ui, bootstyle="primary-toolbutton")
        self.local_rb.pack(side="left", padx=8)

        # YouTube options
        self.yt_frame = tb.Frame(self.source_frame)

        yt_mode_row = tb.Frame(self.yt_frame)
        yt_mode_row.pack(fill="x", pady=(8, 0))
        self.playlist_rb = tb.Radiobutton(yt_mode_row, text="", value="playlist", variable=self.yt_mode,
                                          bootstyle="success-toolbutton")
        self.playlist_rb.pack(side="left")
        self.single_rb = tb.Radiobutton(yt_mode_row, text="", value="video", variable=self.yt_mode,
                                        bootstyle="success-toolbutton")
        self.single_rb.pack(side="left", padx=8)
        self.quality_lbl = tb.Label(yt_mode_row, text="")
        self.quality_lbl.pack(side="left", padx=(16, 4))
        tb.Combobox(yt_mode_row, textvariable=self.video_quality, values=VIDEO_QUALITIES,
                    state="readonly", width=8).pack(side="left")

        url_row = tb.Frame(self.yt_frame)
        url_row.pack(fill="x", pady=(8, 0))
        self.url_lbl = tb.Label(url_row, text="")
        self.url_lbl.pack(side="left", padx=(0, 10))
        self.url_entry = tb.Entry(url_row, textvariable=self.url)
        self.url_entry.pack(side="left", fill="x", expand=True)

        # Local file options
        self.local_frame = tb.Frame(self.source_frame)

        local_row = tb.Frame(self.local_frame)
        local_row.pack(fill="x", pady=(8, 0))
        self.path_lbl = tb.Label(local_row, text="")
        self.path_lbl.pack(side="left", padx=(0, 10))
        self.local_entry = tb.Entry(local_row, textvariable=self.local_path)
        self.local_entry.pack(side="left", fill="x", expand=True)
        self.local_entry.bind("<FocusOut>", lambda _e: self._sync_local_entry())
        self.local_entry.bind("<Return>", lambda _e: self._sync_local_entry())
        self.file_btn = tb.Button(local_row, text="", command=self._pick_local_file, bootstyle="secondary")
        self.file_btn.pack(side="left", padx=4)
        self.folder_btn = tb.Button(local_row, text="", command=self._pick_local_folder, bootstyle="secondary")
        self.folder_btn.pack(side="left")

        local_list = tb.Frame(self.local_frame)
        local_list.pack(fill="both", pady=(6, 0))
        self.local_files_hint = tb.Label(local_list, text="", bootstyle="secondary")
        self.local_files_hint.pack(anchor="w")
        self.local_files_list = tk.Listbox(local_list, height=6, activestyle="none")
        self.local_files_list.pack(side="left", fill="both", expand=True, pady=(4, 0))
        self.local_files_list.bind("<Double-Button-1>", self._on_local_file_double_click)
        self.local_files_scroll = tb.Scrollbar(local_list, orient="vertical", command=self.local_files_list.yview)
        self.local_files_scroll.pack(side="right", fill="y", pady=(4, 0))
        self.local_files_list.configure(yscrollcommand=self.local_files_scroll.set)
        self.local_files_list.bind("<<ListboxSelect>>", self._on_local_file_selected)

        self.conf_meta_frame = tb.Labelframe(self.local_frame, text="", padding=8)
        self.conf_meta_frame.pack(fill="x", pady=(6, 0))

        conf_speaker_row = tb.Frame(self.conf_meta_frame)
        conf_speaker_row.pack(fill="x")
        self.conf_speaker_lbl = tb.Label(conf_speaker_row, text="")
        self.conf_speaker_lbl.pack(side="left", padx=(0, 6))
        self.conf_speaker_entry = tb.Entry(conf_speaker_row, textvariable=self.conf_speaker)
        self.conf_speaker_entry.pack(side="left", fill="x", expand=True)
        self.conf_speaker_entry.bind("<FocusOut>", lambda _e: self._save_conference_meta_from_ui())
        self.conf_speaker_entry.bind("<Return>", lambda _e: self._save_conference_meta_from_ui())

        conf_topic_row = tb.Frame(self.conf_meta_frame)
        conf_topic_row.pack(fill="both", pady=(6, 0))
        self.conf_topic_lbl = tb.Label(conf_topic_row, text="")
        self.conf_topic_lbl.pack(side="left", padx=(0, 6), anchor="n")
        self.conf_topic_txt = tk.Text(conf_topic_row, height=4, wrap="word")
        self.conf_topic_txt.pack(side="left", fill="both", expand=True)
        self.conf_topic_txt.bind("<FocusOut>", lambda _e: self._save_conference_meta_from_ui())

        # Output directory & mode
        row_out = tb.Frame(self.source_frame)
        row_out.pack(fill="x", pady=(8, 0))
        self.output_lbl = tb.Label(row_out, text="")
        self.output_lbl.pack(side="left", padx=(0, 10))
        tb.Entry(row_out, textvariable=self.out_dir).pack(side="left", fill="x", expand=True)
        self.pick_out_btn = tb.Button(row_out, text="", command=self._pick_out, bootstyle="secondary")
        self.pick_out_btn.pack(side="left", padx=8)
        self.mode_lbl = tb.Label(row_out, text="")
        self.mode_lbl.pack(side="left", padx=(8, 4))
        self.output_combo = tb.Combobox(row_out, textvariable=self.output_mode, values=[],
                                        state="readonly", width=20)
        self.output_combo.pack(side="left")
        self.output_combo.bind("<<ComboboxSelected>>", lambda _e: self._on_output_mode_changed())

        prefix_row = tb.Frame(self.source_frame)
        prefix_row.pack(fill="x", pady=(6, 0))
        self.prefix_lbl = tb.Label(prefix_row, text="")
        self.prefix_lbl.pack(side="left", padx=(0, 10))
        self.prefix_entry = tb.Entry(prefix_row, textvariable=self.output_prefix, width=24)
        self.prefix_entry.pack(side="left")
        self.prefix_hint = tb.Label(prefix_row, text="", bootstyle="secondary")
        self.prefix_hint.pack(side="left", padx=(8, 0))

        self.mode_hint = tb.Label(self.source_frame, text="", bootstyle="secondary")
        self.mode_hint.pack(anchor="w", pady=(6, 0))
        self.keep_originals_chk = tb.Checkbutton(self.source_frame, text="", variable=self.keep_originals)
        self.keep_originals_chk.pack(anchor="w", pady=(4, 0))

        # Options row
        opt = tb.Frame(root)
        opt.pack(fill="x", pady=12)

        # Transcription options (always relevant)
        self.transcribe_frame = tb.Labelframe(opt, text="", padding=10)
        self.transcribe_frame.pack(side="left", fill="both", expand=True, padx=(0, 10))

        transcribe_row = tb.Frame(self.transcribe_frame)
        transcribe_row.pack(fill="x")
        self.speech_lang_lbl = tb.Label(transcribe_row, text="")
        self.speech_lang_lbl.pack(side="left", padx=(0, 6))
        tb.Combobox(transcribe_row, textvariable=self.source_lang, values=SOURCE_LANGUAGES,
                    width=8).pack(side="left")
        self.summary_lang_lbl = tb.Label(transcribe_row, text="")
        self.summary_lang_lbl.pack(side="left", padx=(16, 6))
        self.summary_lang_combo = tb.Combobox(
            transcribe_row,
            textvariable=self.summary_lang_label,
            values=[],
            state="readonly",
            width=18,
        )
        self.summary_lang_combo.pack(side="left")
        self.summary_lang_combo.bind("<<ComboboxSelected>>", lambda _e: self._on_summary_lang_changed())

        options_row = tb.Frame(self.transcribe_frame)
        options_row.pack(fill="x", pady=(8, 0))
        self.clean_text_chk = tb.Checkbutton(options_row, text="", variable=self.clean_text)
        self.clean_text_chk.pack(anchor="w")
        self.export_md_chk = tb.Checkbutton(options_row, text="", variable=self.export_md)
        self.export_md_chk.pack(anchor="w")
        self.summary_pack_chk = tb.Checkbutton(options_row, text="", variable=self.summary_pack)
        self.summary_pack_chk.pack(anchor="w")
        self.notify_chk = tb.Checkbutton(options_row, text="", variable=self.notify_on_done)
        self.notify_chk.pack(anchor="w")

        split_row = tb.Frame(self.transcribe_frame)
        split_row.pack(fill="x", pady=(6, 0))
        self.split_lbl = tb.Label(split_row, text="")
        self.split_lbl.pack(side="left", padx=(0, 6))
        self.split_spin = tb.Spinbox(split_row, from_=0, to=120, textvariable=self.split_minutes, width=6)
        self.split_spin.pack(side="left")

        # Translation (hidden for transcript-only mode)
        self.trans_frame = tb.Labelframe(opt, text="", padding=10)
        self.trans_frame.pack(side="left", fill="both", expand=True, padx=(0, 10))

        trans_row1 = tb.Frame(self.trans_frame)
        trans_row1.pack(fill="x")
        self.target_lang_lbl = tb.Label(trans_row1, text="")
        self.target_lang_lbl.pack(side="left", padx=(0, 6))
        tb.Combobox(trans_row1, textvariable=self.target_lang, values=list(TRANSLATION_MODELS.keys()),
                    state="readonly", width=10).pack(side="left")

        trans_row2 = tb.Frame(self.trans_frame)
        trans_row2.pack(fill="x", pady=(8, 0))
        self.batch_lbl = tb.Label(trans_row2, text="")
        self.batch_lbl.pack(side="left", padx=(0, 6))
        tb.Spinbox(trans_row2, from_=4, to=64, textvariable=self.batch, width=6).pack(side="left")

        # Subtitles mode (only for video+subtitles mode)
        self.subs_frame = tb.Labelframe(opt, text="", padding=10)
        self.subs_frame.pack(side="left", fill="both", expand=True, padx=(0, 10))

        self.soft_rb = tb.Radiobutton(self.subs_frame, text="", value="soft",
                                      variable=self.subtitle_mode, command=self._update_subtitle_options)
        self.soft_rb.pack(anchor="w")
        self.hard_rb = tb.Radiobutton(self.subs_frame, text="", value="hard",
                                      variable=self.subtitle_mode, command=self._update_subtitle_options)
        self.hard_rb.pack(anchor="w")

        # Hard subtitle style options
        self.style_frame = tb.Frame(self.subs_frame)

        style_row1 = tb.Frame(self.style_frame)
        style_row1.pack(fill="x", pady=(8, 0))
        self.font_lbl = tb.Label(style_row1, text="")
        self.font_lbl.pack(side="left")
        tb.Combobox(style_row1, textvariable=self.sub_font,
                    values=["Arial", "Helvetica", "Verdana", "Tahoma", "Times New Roman", "Georgia"],
                    width=12).pack(side="left", padx=4)
        self.size_lbl = tb.Label(style_row1, text="")
        self.size_lbl.pack(side="left", padx=(8, 0))
        tb.Spinbox(style_row1, from_=12, to=72, textvariable=self.sub_size, width=4).pack(side="left", padx=4)

        style_row2 = tb.Frame(self.style_frame)
        style_row2.pack(fill="x", pady=(4, 0))
        self.color_lbl = tb.Label(style_row2, text="")
        self.color_lbl.pack(side="left")
        self.color_btn = tb.Button(style_row2, text="■", width=3, command=self._pick_sub_color)
        self.color_btn.pack(side="left", padx=4)
        self.outline_lbl = tb.Label(style_row2, text="")
        self.outline_lbl.pack(side="left", padx=(8, 0))
        self.outline_color_btn = tb.Button(style_row2, text="■", width=3, command=self._pick_outline_color)
        self.outline_color_btn.pack(side="left", padx=4)
        self.width_lbl = tb.Label(style_row2, text="")
        self.width_lbl.pack(side="left", padx=(8, 0))
        tb.Spinbox(style_row2, from_=0, to=5, textvariable=self.sub_outline_width, width=3).pack(side="left", padx=4)

        self._update_color_buttons()

        # Model & GPU
        self.mdl_frame = tb.Labelframe(opt, text="", padding=10)
        self.mdl_frame.pack(side="left", fill="both", expand=True)

        row_m0 = tb.Frame(self.mdl_frame)
        row_m0.pack(fill="x")
        self.quick_model_lbl = tb.Label(row_m0, text="")
        self.quick_model_lbl.pack(side="left", padx=(0, 6))
        self.quick_model_cb = tb.Combobox(row_m0, textvariable=self.quick_model, values=[],
                                          state="readonly", width=14)
        self.quick_model_cb.pack(side="left")
        self.quick_model_cb.bind("<<ComboboxSelected>>", lambda _e: self._on_quick_model_changed())

        row_m1 = tb.Frame(self.mdl_frame)
        row_m1.pack(fill="x", pady=(8, 0))
        self.gpu_chk = tb.Checkbutton(row_m1, text="", variable=self.use_gpu)
        self.gpu_chk.pack(side="left")
        self.detect_btn = tb.Button(row_m1, text="", command=lambda: self._refresh_gpu_badge(force=True), bootstyle="secondary")
        self.detect_btn.pack(side="right")

        row_m2 = tb.Frame(self.mdl_frame)
        row_m2.pack(fill="x", pady=(8, 0))
        self.auto_model_chk = tb.Checkbutton(row_m2, text="", variable=self.auto_model, command=self._apply_auto_model)
        self.auto_model_chk.pack(side="left")

        row_m3 = tb.Frame(self.mdl_frame)
        row_m3.pack(fill="x", pady=(8, 0))
        self.model_lbl = tb.Label(row_m3, text="")
        self.model_lbl.pack(side="left", padx=(0, 4))
        self.model_cb = tb.Combobox(row_m3, textvariable=self.model,
                                     values=["tiny", "base", "small", "medium", "large-v3"],
                                     state="readonly", width=10)
        self.model_cb.pack(side="left")
        self.model_cb.bind("<<ComboboxSelected>>", lambda _e: self._on_model_changed())

        # Actions
        actions = tb.Frame(root)
        actions.pack(fill="x", pady=(0, 8))
        self.start_btn = tb.Button(actions, text="", command=self.start, bootstyle="success")
        self.start_btn.pack(side="left")
        self.stop_btn = tb.Button(actions, text="", command=self.stop, bootstyle="danger", state="disabled")
        self.stop_btn.pack(side="left", padx=8)

        self.update_models_btn = tb.Button(actions, text="", command=self.update_models, bootstyle="warning")
        self.update_models_btn.pack(side="left", padx=8)
        self.open_output_btn = tb.Button(actions, text="", command=self._open_output_dir, bootstyle="secondary")
        self.open_output_btn.pack(side="left", padx=(0, 8))
        self.open_data_btn = tb.Button(actions, text="", command=self._open_app_data_dir, bootstyle="secondary")
        self.open_data_btn.pack(side="left")
        self.show_log_chk = tb.Checkbutton(actions, text="", variable=self.show_log, command=self._toggle_log)
        self.show_log_chk.pack(side="right")

        # System usage
        self.sys_frame = tb.Labelframe(root, text="", padding=10)
        self.sys_frame.pack(fill="x", pady=(0, 8))

        cpu_row = tb.Frame(self.sys_frame)
        cpu_row.pack(fill="x")
        self.cpu_lbl = tb.Label(cpu_row, text="")
        self.cpu_lbl.pack(side="left", padx=(0, 10))
        self.cpu_bar = tb.Progressbar(cpu_row, mode="determinate", length=300)
        self.cpu_bar.pack(side="left", fill="x", expand=True)
        self.cpu_val = tb.Label(cpu_row, text="—")
        self.cpu_val.pack(side="left", padx=(8, 0))
        self.cpu_bar["maximum"] = 100

        ram_row = tb.Frame(self.sys_frame)
        ram_row.pack(fill="x", pady=(6, 0))
        self.ram_lbl = tb.Label(ram_row, text="")
        self.ram_lbl.pack(side="left", padx=(0, 10))
        self.ram_bar = tb.Progressbar(ram_row, mode="determinate", length=300)
        self.ram_bar.pack(side="left", fill="x", expand=True)
        self.ram_val = tb.Label(ram_row, text="—")
        self.ram_val.pack(side="left", padx=(8, 0))
        self.ram_bar["maximum"] = 100

        gpu_row = tb.Frame(self.sys_frame)
        gpu_row.pack(fill="x", pady=(6, 0))
        self.gpu_util_lbl = tb.Label(gpu_row, text="")
        self.gpu_util_lbl.pack(side="left", padx=(0, 10))
        self.gpu_util_bar = tb.Progressbar(gpu_row, mode="determinate", length=300)
        self.gpu_util_bar.pack(side="left", fill="x", expand=True)
        self.gpu_util_val = tb.Label(gpu_row, text="—")
        self.gpu_util_val.pack(side="left", padx=(8, 0))
        self.gpu_util_bar["maximum"] = 100

        # Progress
        self.prog_frame = tb.Labelframe(root, text="", padding=10)
        self.prog_frame.pack(fill="x")

        row_p1 = tb.Frame(self.prog_frame)
        row_p1.pack(fill="x")
        self.overall_lbl_title = tb.Label(row_p1, text="")
        self.overall_lbl_title.pack(side="left", padx=(0, 10))
        self.overall_bar = tb.Progressbar(row_p1, mode="determinate", length=600)
        self.overall_bar.pack(side="left", fill="x", expand=True)
        self.overall_lbl = tb.Label(row_p1, text="0/0")
        self.overall_lbl.pack(side="left", padx=10)

        row_p2 = tb.Frame(self.prog_frame)
        row_p2.pack(fill="x", pady=(8, 0))
        self.activity_lbl_title = tb.Label(row_p2, text="")
        self.activity_lbl_title.pack(side="left", padx=(0, 10))
        self.step_lbl = tb.Label(row_p2, text="—")
        self.step_lbl.pack(side="left", padx=(0, 10))
        self._current_step_text = "—"
        self.step_bar = tb.Progressbar(row_p2, mode="determinate", length=600)
        self.step_bar.pack(side="left", fill="x", expand=True)
        self.eta_lbl_title = tb.Label(row_p2, text="")
        self.eta_lbl_title.pack(side="left", padx=(10, 4))
        self.eta_lbl = tb.Label(row_p2, text="—")
        self.eta_lbl.pack(side="left")

        self.status_lbl = tb.Label(root, text="", bootstyle="secondary")
        self.status_lbl.pack(anchor="w", pady=(10, 0))

        # Log (hidden by default)
        self.log_wrap = tb.Labelframe(root, text="Log", padding=10)
        self.log_txt = tb.Text(self.log_wrap, height=8)
        self.log_txt.pack(fill="both", expand=True)

        self._apply_auto_model()
        self._apply_translations()
        self._toggle_log()

    def _update_source_ui(self):
        mode = normalize_output_mode(self.output_mode_key.get())
        is_conference = (mode == "conference")

        if is_conference:
            self.source_mode.set("local")
            self.youtube_rb.configure(state="disabled")
            self.local_rb.configure(state="disabled")
        else:
            self.youtube_rb.configure(state="normal")
            self.local_rb.configure(state="normal")

        use_youtube = (self.source_mode.get() == "youtube") and not is_conference
        if use_youtube:
            self.local_frame.pack_forget()
            self.yt_frame.pack(fill="x", after=self.yt_frame.master.winfo_children()[0])
        else:
            self.yt_frame.pack_forget()
            self.local_frame.pack(fill="x", after=self.local_frame.master.winfo_children()[0])

        # Conference mode is folder-first
        self.file_btn.configure(state="disabled" if is_conference else "normal")

        show_keep_originals = use_youtube
        if show_keep_originals:
            if not self.keep_originals_chk.winfo_ismapped():
                self.keep_originals_chk.pack(anchor="w", pady=(4, 0), after=self.mode_hint)
        else:
            self.keep_originals_chk.pack_forget()

    def _update_subtitle_options(self):
        """Show/hide hard subtitle style options"""
        if self.subtitle_mode.get() == "hard" and normalize_output_mode(self.output_mode_key.get()) == "video_subs":
            self.style_frame.pack(fill="x", pady=(8, 0))
        else:
            self.style_frame.pack_forget()

    def _update_mode_ui(self):
        mode = normalize_output_mode(self.output_mode_key.get())
        self.output_mode_key.set(mode)

        # Re-pack option frames in a clear, mode-dependent order
        self.transcribe_frame.pack_forget()
        self.trans_frame.pack_forget()
        self.subs_frame.pack_forget()
        self.mdl_frame.pack_forget()

        self.transcribe_frame.pack(side="left", fill="both", expand=True, padx=(0, 10))

        if mode == "conference":
            hint = self.t("mode.hint.conference")
            self.soft_rb.configure(state="disabled")
            self.hard_rb.configure(state="disabled")
            self.style_frame.pack_forget()
        elif mode == "video_subs":
            self.trans_frame.pack(side="left", fill="both", expand=True, padx=(0, 10))
            self.subs_frame.pack(side="left", fill="both", expand=True, padx=(0, 10))
            hint = self.t("mode.hint.video_subs")
            self.soft_rb.configure(state="normal")
            self.hard_rb.configure(state="normal")
        elif mode == "srt_only":
            self.trans_frame.pack(side="left", fill="both", expand=True, padx=(0, 10))
            hint = self.t("mode.hint.srt_only")
            self.soft_rb.configure(state="disabled")
            self.hard_rb.configure(state="disabled")
        else:
            hint = self.t("mode.hint.txt_only")
            self.soft_rb.configure(state="disabled")
            self.hard_rb.configure(state="disabled")
            self.style_frame.pack_forget()

        self.mdl_frame.pack(side="left", fill="both", expand=True)
        self.mode_hint.configure(text=f"{self.t('mode.hint.prefix')} {hint}")
        self._update_subtitle_options()
        self._update_source_ui()
        self._update_conference_meta_visibility()

    def _on_output_mode_changed(self):
        selected_label = self.output_mode.get()
        self.output_mode_key.set(output_mode_label_to_key(selected_label))
        self._update_mode_ui()

    def _on_summary_lang_changed(self):
        selected_label = self.summary_lang_label.get()
        new_key = summary_lang_label_to_key(selected_label)
        self.summary_lang_key.set(new_key)
        self._save_current_config()

    def _update_conference_meta_visibility(self):
        is_conference = (normalize_output_mode(self.output_mode_key.get()) == "conference")
        if is_conference:
            if not self.conf_meta_frame.winfo_ismapped():
                self.conf_meta_frame.pack(fill="x", pady=(6, 0))
            self._set_conference_meta_state(self._conf_selected_path is not None)
        else:
            if self.conf_meta_frame.winfo_ismapped():
                self._save_conference_meta_from_ui()
                self.conf_meta_frame.pack_forget()

    def _update_color_buttons(self):
        """Update color button appearance"""
        self.color_btn.configure(text="■")
        self.outline_color_btn.configure(text="■")
        # Note: ttkbootstrap doesn't easily support changing button foreground color
        # The color is shown via the sub_color/sub_outline_color variables

    def _pick_sub_color(self):
        color = colorchooser.askcolor(color=self.sub_color.get(), title=self.t("colorpicker.subs"))[1]
        if color:
            self.sub_color.set(color)
            self._update_color_buttons()

    def _pick_outline_color(self):
        color = colorchooser.askcolor(color=self.sub_outline_color.get(), title=self.t("colorpicker.outline"))[1]
        if color:
            self.sub_outline_color.set(color)
            self._update_color_buttons()

    def _toggle_log(self):
        if self.show_log.get():
            self.log_wrap.pack(fill="both", expand=True, pady=(10, 0))
        else:
            self.log_wrap.pack_forget()

    def _refresh_gpu_badge(self, force: bool = False):
        if self._gpu_check_thread and self._gpu_check_thread.is_alive() and not force:
            return
        if self._gpu_info_cache and not force:
            self._apply_gpu_info(self._gpu_info_cache)
            return

        self.gpu_badge.configure(text=self.t("performance.gpu_detecting"), bootstyle="secondary")

        def worker_detect():
            gi = get_gpu_info()
            self._gpu_info_cache = gi
            self._apply_gpu_info_async(gi)

        self._gpu_check_thread = threading.Thread(target=worker_detect, daemon=True)
        self._gpu_check_thread.start()

    def _apply_gpu_info_async(self, gi: GpuInfo):
        def apply():
            if not self.winfo_exists():
                return
            self._apply_gpu_info(gi)

        self.after(0, apply)

    def _apply_gpu_info(self, gi: GpuInfo):
        if gi.available:
            self.gpu_badge.configure(text=f"GPU: {gi.name} | VRAM: {gi.vram_gb:.1f} GB", bootstyle="success")
        else:
            self.gpu_badge.configure(text=self.t("performance.gpu_unavailable"), bootstyle="secondary")
        self._apply_auto_model(gi)

    def _apply_auto_model(self, gi: Optional[GpuInfo] = None):
        if not self.auto_model.get():
            self.model_cb.configure(state="readonly")
            self.quick_model_cb.configure(state="readonly")
            self._sync_quick_model_label()
            return
        self.model_cb.configure(state="disabled")
        self.quick_model_cb.configure(state="disabled")

        if not self.use_gpu.get():
            self.model.set("small")
            self.quick_model_key.set(quick_model_key_for_model(self.model.get()))
            self._sync_quick_model_label()
            return

        info = gi or self._gpu_info_cache
        if info is None:
            # Keep the current selection until GPU detection runs.
            self.quick_model_key.set(quick_model_key_for_model(self.model.get()))
            self._sync_quick_model_label()
            return

        if info.available:
            self.model.set(auto_whisper_model(info.vram_gb))
        else:
            self.model.set("small")

        self.quick_model_key.set(quick_model_key_for_model(self.model.get()))
        self._sync_quick_model_label()


    def _sync_quick_model_label(self):
        key = normalize_quick_model(self.quick_model_key.get())
        self.quick_model_key.set(key)
        table = I18N.get(self.lang.get(), I18N["en"])
        self.quick_model.set(table.get(f"quick_model.{key}", key))

    def _on_quick_model_changed(self):
        key = quick_model_label_to_key(self.quick_model.get())
        self.quick_model_key.set(key)
        self.auto_model.set(False)
        self.model.set(QUICK_MODEL_MAP.get(key, "medium"))
        self._apply_auto_model()
        self._save_current_config()

    def _on_model_changed(self):
        if self.auto_model.get():
            return
        if self.model.get() not in QUICK_MODEL_MAP.values():
            return
        self.quick_model_key.set(quick_model_key_for_model(self.model.get()))
        self._sync_quick_model_label()
        self._save_current_config()

    def _open_output_dir(self):
        base_from_out_dir = Path(self.out_dir.get()) / "transcribemate_outputs"
        target = self.final_base_dir if self.final_base_dir.exists() else base_from_out_dir
        if not target.exists():
            target = Path(self.out_dir.get())
        try:
            if sys.platform == "win32":
                os.startfile(target)  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(target)])
            else:
                subprocess.Popen(["xdg-open", str(target)])
        except Exception as exc:
            self._show_error_async(str(exc))

    def _open_app_data_dir(self):
        target = user_data_dir()
        try:
            if sys.platform == "win32":
                os.startfile(target)  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(target)])
            else:
                subprocess.Popen(["xdg-open", str(target)])
        except Exception as exc:
            self._show_error_async(str(exc))

    def _pick_out(self):
        d = filedialog.askdirectory(initialdir=self.out_dir.get())
        if d:
            self._save_conference_meta_from_ui()
            self.out_dir.set(d)
            self.final_base_dir = Path(d) / "transcribemate_outputs"

    def _pick_local_file(self):
        f = filedialog.askopenfilename(
            title=self.t("filepicker.title"),
            filetypes=[
                (self.t("filepicker.media"), "*.mp4 *.mkv *.webm *.mov *.avi *.m4v *.flv *.mp3 *.wav *.m4a *.aac *.flac *.ogg *.opus *.wma"),
                (self.t("filepicker.all"), "*.*"),
            ]
        )
        if f:
            self._set_local_files([Path(f)])

    def _pick_local_folder(self):
        d = filedialog.askdirectory(title=self.t("folderpicker.title"))
        if d:
            self._set_local_path(Path(d))

    # -------- actions --------
    def update_models(self):
        if Messagebox.okcancel(self.t("dialog.update_models.title"), self.t("dialog.update_models.body")) != "OK":
            return
        try:
            force_refresh_models(self.q_log)
            Messagebox.show_info(self.t("dialog.ok"), self.t("dialog.update_models.done"))
        except Exception as e:
            Messagebox.show_error(self.t("dialog.error"), str(e))

    def start(self):
        if self.worker and self.worker.is_alive():
            Messagebox.show_error(self.t("dialog.error"), self.t("error.already_running"))
            return

        out_root = Path(self.out_dir.get())
        output_mode = normalize_output_mode(self.output_mode_key.get())
        self.output_mode_key.set(output_mode)
        is_conference = (output_mode == "conference")

        if is_conference:
            self.source_mode.set("local")
            self._update_source_ui()

        source_mode = self.source_mode.get()

        if not out_root.exists():
            Messagebox.show_error(self.t("dialog.error"), self.t("error.out_dir_missing"))
            return

        # Validate source
        if is_conference:
            local_path_str = self.local_path.get().strip()
            if not local_path_str:
                Messagebox.show_error(self.t("dialog.error"), self.t("error.enter_path"))
                return
            local_path_obj = Path(local_path_str)
            if not local_path_obj.exists():
                Messagebox.show_error(self.t("dialog.error"), self.t("error.path_missing"))
                return
            if not local_path_obj.is_dir():
                Messagebox.show_error(self.t("dialog.error"), self.t("error.conference_requires_folder"))
                return
        else:
            if source_mode == "youtube":
                url = self.url.get().strip()
                if not url:
                    Messagebox.show_error(self.t("dialog.error"), self.t("error.enter_url"))
                    return
                if not _YT_URL_PATTERN.match(url):
                    Messagebox.show_error(self.t("dialog.error"), self.t("error.invalid_url"))
                    return
            else:
                if self._local_files:
                    missing = [p for p in self._local_files if not p.exists()]
                    if missing:
                        Messagebox.show_error(self.t("dialog.error"), self.t("error.path_missing"))
                        return
                else:
                    local_path = self.local_path.get().strip()
                    if not local_path:
                        Messagebox.show_error(self.t("dialog.error"), self.t("error.enter_path"))
                        return
                    if not Path(local_path).exists():
                        Messagebox.show_error(self.t("dialog.error"), self.t("error.path_missing"))
                        return

        is_youtube = (source_mode == "youtube") and not is_conference

        try:
            ensure_tools(is_youtube)
        except Exception as e:
            Messagebox.show_error(self.t("dialog.error"), str(e))
            return

        self.final_base_dir = out_root / "transcribemate_outputs"
        self._save_conference_meta_from_ui()
        self._ensure_conference_meta_loaded()
        conference_meta = dict(self._conf_meta)
        self._save_current_config()
        self.stop_flag.clear()
        self.start_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")
        self.q_status(self.t("status.running"))

        # Collect settings
        is_playlist = (self.yt_mode.get() == "playlist")
        url = self.url.get().strip() if is_youtube else None
        local_path = Path(self.local_path.get().strip()) if not is_youtube else None
        local_files = list(self._local_files) if not is_youtube else []
        prefer_gpu = bool(self.use_gpu.get())
        model = self.model.get()
        src_language = self.source_lang.get().strip() or "auto"
        target_lang = self.target_lang.get()
        summary_lang = normalize_summary_lang(self.summary_lang_key.get())
        translation_model = TRANSLATION_MODELS.get(target_lang, TRANSLATION_MODELS["en→cs"])
        lang_code = LANG_CODES.get(target_lang, "ces")
        batch = int(self.batch.get())
        sub_mode = self.subtitle_mode.get()
        quality = self.video_quality.get()
        clean_text = bool(self.clean_text.get())
        export_md = bool(self.export_md.get())
        generate_summary_pack = bool(self.summary_pack.get())
        try:
            split_minutes = max(0, int(self.split_minutes.get()))
        except Exception:
            split_minutes = 0
        keep_originals = bool(self.keep_originals.get()) and is_youtube
        final_base_dir = self.final_base_dir

        # Subtitle style
        sub_font = self.sub_font.get()
        sub_size = self.sub_size.get()
        sub_color = self.sub_color.get()
        sub_outline_color = self.sub_outline_color.get()
        sub_outline_width = self.sub_outline_width.get()
        t_local = self.t

        def worker():
            nonlocal model
            workdir: Optional[Path] = None
            downloaded_files: List[Path] = []

            def step_with_file(step_key: str, idx: int, total: int, name: str) -> str:
                return f"{t_local(step_key)} — {idx}/{total}: {name}"

            try:
                self.q_log("[INFO] Starting TranscribeMate pipeline\n")
                self.q_log(f"[INFO] Mode: {output_mode}\n")
                self.q_log(f"[INFO] Output root: {out_root}\n")
                if output_mode == "conference" and generate_summary_pack:
                    self.q_log("[INFO] Conference Flow: transcripts + summary pack + Confluence templates\n")
                self.q_log(
                    f"[INFO] Settings: clean_text={clean_text}, export_md={export_md}, summary_pack={generate_summary_pack}, split_minutes={split_minutes}\n"
                )
                self.q_log(
                    f"[INFO] Model: {model} | GPU requested: {prefer_gpu} | Auto model: {self.auto_model.get()}\n"
                )
                self.q_log(f"[INFO] Speech language: {src_language}\n")

                if self.auto_model.get():
                    if prefer_gpu:
                        self.q_step(t_local("step.detect_gpu"))
                        self.q_step_indeterminate(True)
                        self.q_log("[INFO] Detecting GPU capabilities...\n")
                        gi = get_gpu_info()
                        self.q_step_indeterminate(False)
                        self._gpu_info_cache = gi
                        self.q_gpu_info(gi)
                        if gi.available:
                            model = auto_whisper_model(gi.vram_gb)
                            self.q_log(
                                f"[INFO] GPU detected: {gi.name} ({gi.vram_gb:.1f} GB). Auto model -> {model}\n"
                            )
                        else:
                            model = "small"
                            self.q_log("[WARN] GPU not available. Auto model -> small\n")
                    else:
                        model = "small"
                        self.q_log("[INFO] GPU disabled. Auto model -> small\n")

                # Final output directories (user-visible)
                self.q_step(t_local("step.prepare_outputs"))
                self.q_step_indeterminate(True)
                transcripts_dir = final_base_dir / "transcripts"
                subtitles_src_final_dir = final_base_dir / "subtitles_source"
                subtitles_trans_final_dir = final_base_dir / "subtitles_translated"
                videos_final_dir = final_base_dir / "videos"
                originals_dir = final_base_dir / "originals"
                summaries_dir = final_base_dir / "summaries"
                output_dirs = [
                    transcripts_dir,
                    subtitles_src_final_dir,
                    subtitles_trans_final_dir,
                    videos_final_dir,
                    originals_dir,
                ]
                if generate_summary_pack:
                    output_dirs.append(summaries_dir)
                for d in output_dirs:
                    d.mkdir(parents=True, exist_ok=True)
                self.q_step_indeterminate(False)

                # Temporary working directory (always cleaned up)
                workdir = Path(tempfile.mkdtemp(prefix="_tm_work_", dir=out_root))
                downloads_dir = workdir / "downloads"
                srt_src_dir = workdir / "srt_source"
                srt_trans_dir = workdir / "srt_translated"
                for d in [downloads_dir, srt_src_dir, srt_trans_dir]:
                    d.mkdir(parents=True, exist_ok=True)

                self.q_log(f"[INFO] Workdir: {workdir}\n")
                self.q_log(f"[INFO] Outputs: {final_base_dir}\n")

                # Get videos
                if is_youtube:
                    self.q_step(f"{t_local('step.download')} — yt-dlp")
                    self.q_step_progress(0)
                    download_single_or_playlist(
                        url,
                        workdir,
                        is_playlist,
                        quality,
                        self.q_log,
                        self.q_step_progress,
                        self.stop_flag,
                    )
                    downloaded_files = list_videos(downloads_dir)
                    videos = downloaded_files
                    self.q_log(f"[INFO] Downloaded media files: {len(videos)}\n")
                else:
                    if not local_path and not local_files:
                        raise RuntimeError(t_local("error.enter_path"))
                    self.q_step(t_local("step.scan_media"))
                    self.q_step_indeterminate(True)
                    if local_files:
                        videos = self._filter_media_files(local_files)
                    elif local_path.is_file():
                        videos = [local_path]
                    else:
                        videos = list_videos(local_path)
                    self.q_step_indeterminate(False)
                    self.q_log(f"[INFO] Media files found: {len(videos)}\n")

                if not videos:
                    raise RuntimeError(t_local("error.no_media"))

                if output_mode == "video_subs" and any(is_audio_file(v) for v in videos):
                    raise RuntimeError(t_local("error.audio_not_supported"))

                translation_needed = output_mode in {"video_subs", "srt_only"}
                lang_suffix = target_lang.split("→")[1] if translation_needed else ""
                self.total_videos = len(videos)
                self.done_videos = 0
                self.q_overall(self.done_videos, self.total_videos)
                self.q_log(f"[INFO] Total items to process: {self.total_videos}\n")

                for i, v in enumerate(videos, 1):
                    if self.stop_flag.is_set():
                        raise RuntimeError(t_local("error.stopped"))

                    self.q_status(f"{t_local('status.processing')} {i}/{len(videos)}: {v.name}")
                    self.q_log(f"\n[INFO] Processing {i}/{len(videos)}: {v.name}\n")
                    prefix = self.output_prefix.get().strip()
                    base_name = timestamped_base_name(v, prefix=prefix)

                    # Transcribe
                    self.q_step(step_with_file("step.transcribe", i, len(videos), v.name))
                    self.q_step_progress(0)
                    transcription = faster_whisper_transcribe(
                        v,
                        srt_src_dir,
                        model,
                        prefer_gpu,
                        src_language,
                        self.q_log,
                        self.q_step_progress,
                        self.stop_flag,
                    )
                    self.q_log(
                        f"[INFO] Transcription done: lang={transcription.detected_lang}, duration={transcription.duration:.1f}s\n"
                    )

                    # Export transcripts (.txt/.md) + summary pack
                    export_step_key = "step.export_with_summary" if generate_summary_pack else "step.export_txt"
                    self.q_step(step_with_file(export_step_key, i, len(videos), v.name))
                    self.q_step_indeterminate(True)
                    speaker = ""
                    topic = ""
                    if output_mode == "conference":
                        entry = conference_meta.get(str(v), {})
                        if isinstance(entry, dict):
                            speaker = entry.get("speaker", "")
                            topic = entry.get("topic", "")
                    export_transcripts(
                        media_path=v,
                        result=transcription,
                        transcripts_dir=transcripts_dir,
                        clean_text=clean_text,
                        export_md=export_md,
                        split_minutes=split_minutes,
                        generate_summary_pack=generate_summary_pack,
                        summaries_dir=summaries_dir,
                        output_mode=output_mode,
                        model_name=model,
                        log=self.q_log,
                        output_prefix=prefix,
                        speaker=speaker,
                        topic=topic,
                        summary_lang=summary_lang,
                    )
                    self.q_step_indeterminate(False)

                    if not translation_needed:
                        self.done_videos += 1
                        self.q_overall(self.done_videos, self.total_videos)
                        continue

                    # Translate
                    step_translate = step_with_file("step.translate", i, len(videos), v.name)
                    self.q_step(f"{step_translate} ({target_lang})")
                    self.q_step_progress(0)
                    srt_trans_tmp = srt_trans_dir / f"{sanitize_filename(v.stem)}.{lang_suffix}.srt"
                    translate_srt(
                        transcription.srt_path,
                        srt_trans_tmp,
                        translation_model,
                        prefer_gpu,
                        batch,
                        self.q_log,
                        self.q_step_progress,
                        self.stop_flag,
                    )

                    # Export SRTs to final folders
                    self.q_step(step_with_file("step.export_srt", i, len(videos), v.name))
                    self.q_step_indeterminate(True)
                    final_srt_src = unique_path(
                        subtitles_src_final_dir,
                        f"{base_name}.{transcription.detected_lang}.srt",
                    )
                    final_srt_trans = unique_path(
                        subtitles_trans_final_dir,
                        f"{base_name}.{lang_suffix}.srt",
                    )
                    shutil.copy(transcription.srt_path, final_srt_src)
                    shutil.copy(srt_trans_tmp, final_srt_trans)
                    self.q_step_indeterminate(False)
                    self.q_log(f"[OK] Saved: {final_srt_src}\n")
                    self.q_log(f"[OK] Saved: {final_srt_trans}\n")

                    if output_mode == "srt_only":
                        self.done_videos += 1
                        self.q_overall(self.done_videos, self.total_videos)
                        continue

                    # Add subtitles to video
                    self.q_step(step_with_file("step.embed", i, len(videos), v.name))
                    suffix = ".soft" if sub_mode == "soft" else ".hard"
                    out_mp4 = unique_path(videos_final_dir, f"{base_name}{suffix}.sub.mp4")
                    if sub_mode == "soft":
                        soft_subtitles(
                            v,
                            srt_trans_tmp,
                            out_mp4,
                            lang_code,
                            self.q_log,
                            self.q_step_indeterminate,
                            self.stop_flag,
                        )
                    else:
                        hard_subtitles(
                            v,
                            srt_trans_tmp,
                            out_mp4,
                            sub_font,
                            sub_size,
                            sub_color,
                            sub_outline_color,
                            sub_outline_width,
                            self.q_log,
                            self.q_step_indeterminate,
                            self.stop_flag,
                        )

                    self.done_videos += 1
                    self.q_overall(self.done_videos, self.total_videos)

                if is_youtube and keep_originals:
                    copy_originals_to_final(downloaded_files, originals_dir, self.q_log)

                self.q_status(f"{t_local('status.done')} {final_base_dir}")
                self.q_step(t_local("step.done"))
                self.q_step_progress(100)
                self.q_notify(
                    t_local("notify.done_title"),
                    t_local("notify.done_body").format(done=self.done_videos, total=self.total_videos),
                )
                if self.notify_on_done.get():
                    self._notify_completion(final_base_dir)
            except Exception as e:
                err_msg = str(e)
                self.q_log(f"\n[ERROR] {err_msg}\n")
                if "Stopped by user" in err_msg or self.stop_flag.is_set():
                    self.q_status(t_local("status.stop"))
                else:
                    self.q_status(t_local("status.error"))
                    self._show_error_async(err_msg)
                    self.q_notify(t_local("notify.error_title"), err_msg[:100])
            finally:
                cleanup_workdir(workdir, self.q_log)
                self.q_step_indeterminate(False)
                self.q_buttons_reset()

        self.worker = threading.Thread(target=worker, daemon=True)
        self.worker.start()

    def stop(self):
        self.stop_flag.set()
        self.q_status(self.t("status.stopping"))
        self.q_log("[INFO] Stop requested...\n")

    def _apply_translations(self):
        self.title(self.t("app.title"))
        self.header_title_lbl.configure(text=self.t("header.title"))
        self.lang_lbl.configure(text=self.t("header.language"))
        self.theme_lbl.configure(text=self.t("header.theme"))

        self.source_frame.configure(text=self.t("source.title"))
        self.youtube_rb.configure(text=self.t("source.youtube"))
        self.local_rb.configure(text=self.t("source.local"))
        self.playlist_rb.configure(text=self.t("source.playlist"))
        self.single_rb.configure(text=self.t("source.single"))
        self.quality_lbl.configure(text=self.t("source.quality"))
        self.url_lbl.configure(text=self.t("source.url"))
        self.path_lbl.configure(text=self.t("source.path"))
        self.file_btn.configure(text=self.t("source.file"))
        self.folder_btn.configure(text=self.t("source.folder"))
        self._update_local_list_hint()
        self.conf_meta_frame.configure(text=self.t("conference.meta.title"))
        self.conf_speaker_lbl.configure(text=self.t("conference.meta.speaker"))
        self.conf_topic_lbl.configure(text=self.t("conference.meta.topic"))
        self.output_lbl.configure(text=self.t("source.output"))
        self.pick_out_btn.configure(text=self.t("source.pick"))
        self.mode_lbl.configure(text=self.t("source.mode"))
        self.keep_originals_chk.configure(text=self.t("source.keep_originals"))
        self.prefix_lbl.configure(text=self.t("source.prefix"))
        self.prefix_hint.configure(text=self.t("source.prefix_hint"))

        self.transcribe_frame.configure(text=self.t("transcribe.title"))
        self.speech_lang_lbl.configure(text=self.t("transcribe.speech_language"))
        self.summary_lang_lbl.configure(text=self.t("transcribe.summary_lang"))
        self.clean_text_chk.configure(text=self.t("transcribe.clean_text"))
        self.export_md_chk.configure(text=self.t("transcribe.export_md"))
        self.summary_pack_chk.configure(text=self.t("transcribe.summary_pack"))
        self.notify_chk.configure(text=self.t("transcribe.notify_done"))
        self.split_lbl.configure(text=self.t("transcribe.split_minutes"))
        self.trans_frame.configure(text=self.t("translate.title"))
        self.target_lang_lbl.configure(text=self.t("translate.target_language"))
        self.batch_lbl.configure(text=self.t("translate.batch"))
        self.subs_frame.configure(text=self.t("subs.title"))
        self.soft_rb.configure(text=self.t("subs.soft"))
        self.hard_rb.configure(text=self.t("subs.hard"))
        self.font_lbl.configure(text=self.t("subs.font"))
        self.size_lbl.configure(text=self.t("subs.size"))
        self.color_lbl.configure(text=self.t("subs.color"))
        self.outline_lbl.configure(text=self.t("subs.outline"))
        self.width_lbl.configure(text=self.t("subs.width"))
        self.mdl_frame.configure(text=self.t("performance.title"))
        self.gpu_chk.configure(text=self.t("performance.gpu"))
        self.detect_btn.configure(text=self.t("performance.detect"))
        self.auto_model_chk.configure(text=self.t("performance.auto_model"))
        self.model_lbl.configure(text=self.t("performance.model"))
        self.quick_model_lbl.configure(text=self.t("performance.quick_model"))

        self.start_btn.configure(text=self.t("actions.start"))
        self.stop_btn.configure(text=self.t("actions.stop"))
        self.update_models_btn.configure(text=self.t("actions.update_models"))
        self.open_output_btn.configure(text=self.t("actions.open_output"))
        self.open_data_btn.configure(text=self.t("actions.open_data"))
        self.show_log_chk.configure(text=self.t("actions.show_log"))

        self.sys_frame.configure(text=self.t("system.title"))
        self.cpu_lbl.configure(text=self.t("system.cpu"))
        self.ram_lbl.configure(text=self.t("system.ram"))
        self.gpu_util_lbl.configure(text=self.t("system.gpu"))

        self.prog_frame.configure(text=self.t("progress.title"))
        self.overall_lbl_title.configure(text=self.t("progress.total"))
        self.activity_lbl_title.configure(text=self.t("progress.activity"))
        self.eta_lbl_title.configure(text=self.t("progress.eta"))
        self.status_lbl.configure(text=self.t("status.ready"))

        table = I18N.get(self.lang.get(), I18N["en"])

        theme_vals = theme_labels(self.lang.get())
        self.theme_combo.configure(values=theme_vals)
        current_theme_key = normalize_theme(self.theme_key.get())
        self.theme_key.set(current_theme_key)
        self.theme_label.set(table.get(f"theme.{current_theme_key}", current_theme_key))

        labels = output_mode_labels(self.lang.get())
        self.output_combo.configure(values=labels)
        current_key = normalize_output_mode(self.output_mode_key.get())
        self.output_mode_key.set(current_key)
        self.output_mode.set(table.get(f"output_mode.{current_key}", current_key))

        quick_labels = quick_model_labels(self.lang.get())
        self.quick_model_cb.configure(values=quick_labels)
        current_quick_key = normalize_quick_model(self.quick_model_key.get())
        self.quick_model_key.set(current_quick_key)
        self._sync_quick_model_label()

        summary_labels = summary_lang_labels(self.lang.get())
        self.summary_lang_combo.configure(values=summary_labels)
        current_summary_key = normalize_summary_lang(self.summary_lang_key.get())
        self.summary_lang_key.set(current_summary_key)
        self.summary_lang_label.set(summary_lang_label_for_key(current_summary_key, self.lang.get()))

        self._update_mode_ui()
        if self._gpu_info_cache:
            self._apply_gpu_info(self._gpu_info_cache)
        else:
            self.gpu_badge.configure(text=self.t("performance.gpu_idle"), bootstyle="secondary")
            self._apply_auto_model()
        self._save_current_config()

    def _maximize_window(self):
        try:
            self.state("zoomed")
        except Exception:
            try:
                self.attributes("-zoomed", True)
            except Exception:
                width = self.winfo_screenwidth()
                height = self.winfo_screenheight()
                self.geometry(f"{width}x{height}+0+0")

    def _start_system_monitor(self):
        try:
            import psutil  # type: ignore

            psutil.cpu_percent(interval=None)
        except Exception:
            LOGGER.debug("psutil not available for system monitor", exc_info=True)
        self._update_system_metrics()

    def _update_system_metrics(self):
        if not self.winfo_exists():
            return

        cpu_pct, ram_pct = self._get_cpu_ram()
        self._apply_metric(self.cpu_bar, self.cpu_val, cpu_pct)
        self._apply_metric(self.ram_bar, self.ram_val, ram_pct)
        self._update_gpu_metric()
        self._schedule_gpu_poll()

        self.after(1000, self._update_system_metrics)

    def _apply_metric(self, bar, label, value: Optional[float]):
        if value is None:
            bar["value"] = 0
            label.configure(text=self.t("system.na"))
            return
        clamped = max(0.0, min(100.0, float(value)))
        bar["value"] = clamped
        label.configure(text=f"{clamped:.0f}%")

    def _get_cpu_ram(self) -> tuple[Optional[float], Optional[float]]:
        try:
            import psutil  # type: ignore

            cpu_pct = psutil.cpu_percent(interval=None)
            ram_pct = psutil.virtual_memory().percent
            return float(cpu_pct), float(ram_pct)
        except Exception:
            LOGGER.debug("Failed to read CPU/RAM metrics", exc_info=True)
            return None, None

    def _update_gpu_metric(self):
        if self._gpu_usage_cache is None:
            self.gpu_util_bar["value"] = 0
            self.gpu_util_val.configure(text=self.t("system.na"))
            return
        usage = max(0.0, min(100.0, float(self._gpu_usage_cache)))
        self.gpu_util_bar["value"] = usage
        if self._gpu_mem_cache:
            used, total = self._gpu_mem_cache
            self.gpu_util_val.configure(text=f"{usage:.0f}% | {used:.1f}/{total:.1f} GB")
        else:
            self.gpu_util_val.configure(text=f"{usage:.0f}%")

    def _schedule_gpu_poll(self):
        if self._gpu_poll_inflight or not self._gpu_smi_available:
            return
        self._gpu_poll_inflight = True
        thread = threading.Thread(target=self._poll_gpu_usage, daemon=True)
        thread.start()

    def _poll_gpu_usage(self):
        usage, mem = self._get_gpu_usage()

        def apply():
            self._gpu_usage_cache = usage
            self._gpu_mem_cache = mem
            self._gpu_poll_inflight = False
            self._update_gpu_metric()

        self.after(0, apply)

    def _get_gpu_usage(self) -> tuple[Optional[float], Optional[tuple[float, float]]]:
        try:
            kwargs = {
                "capture_output": True,
                "text": True,
                "timeout": 1.0,
            }
            if sys.platform == "win32":
                kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
                startupinfo = subprocess.STARTUPINFO()
                startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
                kwargs["startupinfo"] = startupinfo
            result = subprocess.run(
                [
                    "nvidia-smi",
                    "--query-gpu=utilization.gpu,memory.used,memory.total",
                    "--format=csv,noheader,nounits",
                ],
                **kwargs,
            )
            if result.returncode != 0:
                return None, None
            line = result.stdout.strip().splitlines()[0].strip()
            if not line:
                return None, None
            parts = [p.strip() for p in line.split(",")]
            if len(parts) < 3:
                return None, None
            usage = float(parts[0])
            mem_used = float(parts[1]) / 1024.0
            mem_total = float(parts[2]) / 1024.0
            return usage, (mem_used, mem_total)
        except Exception:
            LOGGER.debug("Failed to read GPU usage", exc_info=True)
            return None, None

    def _parse_dnd_paths(self, data: str) -> List[str]:
        try:
            return list(self.tk.splitlist(data))
        except Exception:
            parts = re.findall(r"\{[^}]*\}|[^ ]+", data)
            return [p.strip("{}") for p in parts]

    def _filter_media_files(self, paths: Sequence[Path]) -> List[Path]:
        media_exts = VIDEO_EXTS | AUDIO_EXTS
        return sorted([p for p in paths if p.suffix.lower() in media_exts])

    def _update_local_list(self, items: Sequence[Path]):
        self._local_list_items = list(items)
        self.local_files_list.delete(0, tk.END)
        for p in self._local_list_items:
            self.local_files_list.insert(tk.END, p.name)
        self.local_files_list.selection_clear(0, tk.END)
        self._conf_selected_path = None
        self._clear_conference_meta_fields()
        self._set_conference_meta_state(False)
        self._update_local_list_hint()

    def _update_local_list_hint(self):
        count = len(self._local_list_items)
        if count == 0:
            self.local_files_hint.configure(text=self.t("source.files_hint_empty"))
        else:
            self.local_files_hint.configure(
                text=self.t("source.files_hint_count").format(count=count)
            )

    def _conference_meta_path_for_output(self) -> Path:
        out_root = Path(self.out_dir.get()).expanduser()
        return out_root / "transcribemate_outputs" / "conference_meta.json"

    def _ensure_conference_meta_loaded(self):
        meta_path = self._conference_meta_path_for_output()
        if self._conf_meta_path == meta_path:
            return
        self._conf_meta_path = meta_path
        self._conf_meta = {}
        try:
            if meta_path.exists():
                data = json.loads(meta_path.read_text(encoding="utf-8"))
                if isinstance(data, dict):
                    files = data.get("files", data)
                    if isinstance(files, dict):
                        for key, entry in files.items():
                            if isinstance(entry, dict):
                                speaker = str(entry.get("speaker") or "").strip()
                                topic = str(entry.get("topic") or "").strip()
                            else:
                                speaker = ""
                                topic = ""
                            if speaker or topic:
                                self._conf_meta[key] = {"speaker": speaker, "topic": topic}
        except Exception:
            LOGGER.exception("Failed to load conference metadata from %s", meta_path)
            self._conf_meta = {}

    def _save_conference_meta(self):
        if self._conf_meta_path is None:
            self._conf_meta_path = self._conference_meta_path_for_output()
        try:
            self._conf_meta_path.parent.mkdir(parents=True, exist_ok=True)
            payload = {"version": 1, "files": self._conf_meta}
            self._conf_meta_path.write_text(
                json.dumps(payload, indent=2, ensure_ascii=False),
                encoding="utf-8",
            )
        except Exception:
            LOGGER.exception("Failed to save conference metadata to %s", self._conf_meta_path)

    def _conference_meta_key(self, path: Path) -> str:
        return str(path)

    def _get_conference_meta_for_path(self, path: Path) -> Dict[str, str]:
        self._ensure_conference_meta_loaded()
        return self._conf_meta.get(self._conference_meta_key(path), {})

    def _set_conference_meta_for_path(self, path: Path, speaker: str, topic: str):
        self._ensure_conference_meta_loaded()
        key = self._conference_meta_key(path)
        if speaker or topic:
            self._conf_meta[key] = {"speaker": speaker, "topic": topic}
        else:
            self._conf_meta.pop(key, None)
        self._save_conference_meta()

    def _get_topic_text(self) -> str:
        return self.conf_topic_txt.get("1.0", tk.END).rstrip("\n")

    def _set_topic_text(self, text: str):
        prev_state = self.conf_topic_txt.cget("state")
        self.conf_topic_txt.configure(state="normal")
        self.conf_topic_txt.delete("1.0", tk.END)
        if text:
            self.conf_topic_txt.insert("1.0", text)
        self.conf_topic_txt.configure(state=prev_state)

    def _clear_conference_meta_fields(self):
        self.conf_speaker.set("")
        self._set_topic_text("")

    def _set_conference_meta_state(self, enabled: bool):
        state = "normal" if enabled else "disabled"
        self.conf_speaker_entry.configure(state=state)
        self.conf_topic_txt.configure(state=state)

    def _save_conference_meta_from_ui(self):
        if not self._conf_selected_path:
            return
        speaker = self.conf_speaker.get().strip()
        topic = self._get_topic_text().strip()
        self._set_conference_meta_for_path(self._conf_selected_path, speaker, topic)

    def _on_local_file_selected(self, _event):
        self._save_conference_meta_from_ui()
        selection = self.local_files_list.curselection()
        if not selection:
            self._conf_selected_path = None
            self._clear_conference_meta_fields()
            self._set_conference_meta_state(False)
            return
        idx = selection[0]
        if idx < 0 or idx >= len(self._local_list_items):
            return
        path = self._local_list_items[idx]
        self._conf_selected_path = path
        meta = self._get_conference_meta_for_path(path)
        self.conf_speaker.set(meta.get("speaker", ""))
        self._set_topic_text(meta.get("topic", ""))
        self._set_conference_meta_state(True)

    def _set_local_path(self, path: Path):
        self.local_path.set(str(path))
        self._local_files = []
        items = list_videos(path) if path.is_dir() else [path]
        self._update_local_list(items)

    def _set_local_files(self, files: Sequence[Path]):
        media = self._filter_media_files(files)
        self._local_files = list(media)
        self.local_path.set("")
        self._update_local_list(self._local_files)

    def _sync_local_entry(self):
        path_str = self.local_path.get().strip()
        if not path_str:
            self._local_files = []
            self._update_local_list([])
            return
        path = Path(path_str)
        if not path.exists():
            self._local_files = []
            self._update_local_list([])
            return
        self._set_local_path(path)

    def _on_local_file_double_click(self, _event):
        selection = self.local_files_list.curselection()
        if not selection:
            return
        idx = selection[0]
        if idx < 0 or idx >= len(self._local_list_items):
            return
        self._open_external(self._local_list_items[idx])

    def _open_external(self, target: Path):
        try:
            if sys.platform == "win32":
                os.startfile(target)  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(target)])
            else:
                subprocess.Popen(["xdg-open", str(target)])
        except Exception as exc:
            self._show_error_async(str(exc))

    def _notify_completion(self, output_dir: Path):
        try:
            if sys.platform == "win32":
                import winsound

                winsound.MessageBeep()
            else:
                print("\a", end="", flush=True)
        except Exception:
            LOGGER.debug("Completion beep failed", exc_info=True)
        self._open_external(output_dir)

    def _estimate_eta_text(self, pct: float) -> Optional[str]:
        if pct <= 0.0:
            return None
        if not self._step_started_at:
            return None
        if pct >= 99.9:
            return "0:00"
        elapsed = max(0.0, time.monotonic() - self._step_started_at)
        remaining = elapsed * (100.0 - pct) / max(pct, 0.1)
        return self._format_eta_seconds(remaining)

    def _format_eta_seconds(self, seconds: float) -> str:
        total = max(0, int(seconds))
        hours = total // 3600
        minutes = (total % 3600) // 60
        secs = total % 60
        if hours > 0:
            return f"{hours}:{minutes:02}:{secs:02}"
        return f"{minutes}:{secs:02}"
