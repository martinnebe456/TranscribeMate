"""Tkinter UI for TranscribeMate."""

from __future__ import annotations

import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
from typing import Dict, List, Optional

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
    timestamped_base_name,
    unique_path,
)
from ..core.gpu import GpuInfo, auto_whisper_model, get_gpu_info
from ..core.i18n import (
    APP_NAME,
    I18N,
    LANGUAGES,
    LANG_CODES,
    OUTPUT_MODE_KEYS,
    QUICK_MODEL_KEYS,
    QUICK_MODEL_MAP,
    SOURCE_LANGUAGES,
    TRANSLATION_MODELS,
    VIDEO_QUALITIES,
    normalize_output_mode,
    normalize_quick_model,
    output_mode_label_to_key,
    output_mode_labels,
    quick_model_key_for_model,
    quick_model_label_to_key,
    quick_model_labels,
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

# -----------------------------
# Custom Window with DnD support
# -----------------------------
class DnDWindow(tb.Window):
    """ttkbootstrap Window with drag-and-drop support"""
    def __init__(self, *args, **kwargs):
        # Try to use TkinterDnD, fall back to regular window
        try:
            self._dnd_enabled = True
            # Create a TkinterDnD.Tk instance first
            self._dnd_root = TkinterDnD.Tk()
            self._dnd_root.withdraw()
            super().__init__(*args, **kwargs)
        except Exception:
            self._dnd_enabled = False
            super().__init__(*args, **kwargs)

    def drop_target_register(self, *args):
        if self._dnd_enabled:
            try:
                return self.tk.call('tkdnd::drop_target', 'register', self._w, *args)
            except Exception:
                pass

    def dnd_bind(self, sequence, func):
        if self._dnd_enabled:
            try:
                self.tk.call('tkdnd::bind', self._w, sequence, func)
            except Exception:
                pass


# -----------------------------
# Minimalist modern GUI
# -----------------------------
class App(tb.Window):
    def __init__(self):
        super().__init__(themename="flatly")
        self.title(I18N["en"]["app.title"])
        self.geometry("1050x820")
        self.minsize(1000, 740)

        self.stop_flag = threading.Event()
        self.worker = None
        self.uiq = queue.Queue()
        self.cfg = load_config()

        # State variables
        self.source_mode = tb.StringVar(value="youtube")
        self.yt_mode = tb.StringVar(value="playlist")
        self.url = tb.StringVar()
        self.local_path = tb.StringVar()
        self.out_dir = tb.StringVar(value=self.cfg.out_dir)
        self.lang = tb.StringVar(value=self.cfg.lang)
        self.use_gpu = tb.BooleanVar(value=self.cfg.use_gpu)
        self.auto_model = tb.BooleanVar(value=self.cfg.auto_model)
        self.model = tb.StringVar(value=self.cfg.whisper_model)
        self.quick_model_key = tb.StringVar(value=normalize_quick_model(self.cfg.quick_model))
        self.quick_model = tb.StringVar()
        self.source_lang = tb.StringVar(value=self.cfg.source_lang)
        self.target_lang = tb.StringVar(value=self.cfg.target_lang)
        self.batch = tb.IntVar(value=self.cfg.batch_size)
        self.video_quality = tb.StringVar(value=self.cfg.video_quality)
        self.output_mode_key = tb.StringVar(value=normalize_output_mode(self.cfg.output_mode))
        self.output_mode = tb.StringVar()
        self.clean_text = tb.BooleanVar(value=self.cfg.clean_text)
        self.export_md = tb.BooleanVar(value=self.cfg.export_md)
        self.split_minutes = tb.IntVar(value=self.cfg.split_minutes)
        self.keep_originals = tb.BooleanVar(value=self.cfg.keep_originals)

        self.subtitle_mode = tb.StringVar(value=self.cfg.subtitle_mode)
        self.show_log = tb.BooleanVar(value=True)

        if not self.auto_model.get() and self.model.get() in QUICK_MODEL_MAP.values():
            self.quick_model_key.set(quick_model_key_for_model(self.model.get()))

        # Subtitle style
        self.sub_font = tb.StringVar(value=self.cfg.sub_font)
        self.sub_size = tb.IntVar(value=self.cfg.sub_size)
        self.sub_color = tb.StringVar(value=self.cfg.sub_color)
        self.sub_outline_color = tb.StringVar(value=self.cfg.sub_outline_color)
        self.sub_outline_width = tb.IntVar(value=self.cfg.sub_outline_width)

        self.total_videos = 0
        self.done_videos = 0
        self.final_base_dir = Path(self.out_dir.get()) / "transcribemate_outputs"

        self._gpu_info_cache: Optional[GpuInfo] = None
        self._gpu_check_thread: Optional[threading.Thread] = None

        self._build_ui()
        self._setup_drag_drop()
        self.after(100, self._drain_uiq)
        self._update_source_ui()
        self._update_subtitle_options()

        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _setup_drag_drop(self):
        """Setup drag and drop for URL entry and local path"""
        try:
            # Register the window for drag and drop
            self.drop_target_register(DND_FILES)
            self.dnd_bind('<<Drop>>', self._on_drop)
        except Exception:
            # DnD not available
            pass

    def _on_drop(self, event):
        """Handle dropped files/URLs"""
        data = event.data if hasattr(event, 'data') else str(event)
        # Clean up the path (remove curly braces if present)
        data = data.strip('{}').strip()

        if data.startswith('http'):
            self.source_mode.set("youtube")
            self.url.set(data)
            self._update_source_ui()
        elif Path(data).exists():
            self.source_mode.set("local")
            self.local_path.set(data)
            self._update_source_ui()

    def _on_close(self):
        self._save_current_config()
        self.destroy()

    def _save_current_config(self):
        self.cfg.out_dir = self.out_dir.get()
        self.cfg.lang = self.lang.get()
        self.cfg.use_gpu = self.use_gpu.get()
        self.cfg.auto_model = self.auto_model.get()
        self.cfg.whisper_model = self.model.get()
        self.cfg.quick_model = normalize_quick_model(self.quick_model_key.get())
        self.cfg.source_lang = self.source_lang.get()
        self.cfg.target_lang = self.target_lang.get()
        self.cfg.batch_size = self.batch.get()
        self.cfg.subtitle_mode = self.subtitle_mode.get()
        self.cfg.video_quality = self.video_quality.get()
        self.cfg.output_mode = normalize_output_mode(self.output_mode_key.get())
        self.cfg.clean_text = bool(self.clean_text.get())
        self.cfg.export_md = bool(self.export_md.get())
        try:
            self.cfg.split_minutes = max(0, int(self.split_minutes.get()))
        except Exception:
            self.cfg.split_minutes = 0
        self.cfg.keep_originals = bool(self.keep_originals.get())
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
                if kind == "log":
                    self.log_txt.insert("end", item[1])
                    if self.show_log.get():
                        self.log_txt.see("end")
                elif kind == "status":
                    self.status_lbl.configure(text=item[1])
                elif kind == "step":
                    self.step_lbl.configure(text=item[1])
                    self.step_bar.stop()
                    self.step_bar.configure(mode="determinate")
                    self.step_bar["value"] = 0
                elif kind == "step_progress":
                    v = max(0.0, min(100.0, float(item[1])))
                    if str(self.step_bar["mode"]) != "determinate":
                        self.step_bar.stop()
                        self.step_bar.configure(mode="determinate")
                    self.step_bar["value"] = v
                elif kind == "step_indeterminate":
                    on = bool(item[1])
                    if on:
                        self.step_bar["value"] = 0
                        self.step_bar.configure(mode="indeterminate")
                        self.step_bar.start(12)
                    else:
                        self.step_bar.stop()
                        self.step_bar.configure(mode="determinate")
                        self.step_bar["value"] = 100
                elif kind == "overall":
                    done, total = int(item[1]), int(item[2])
                    self.overall_bar["maximum"] = max(1, total)
                    self.overall_bar["value"] = done
                    self.overall_lbl.configure(text=f"{done}/{total}")
                elif kind == "buttons_reset":
                    self.start_btn.configure(state="normal")
                    self.stop_btn.configure(state="disabled")
                elif kind == "gpu_info":
                    self._apply_gpu_info(item[1])
                elif kind == "notify":
                    show_notification(item[1], item[2])
        except queue.Empty:
            pass
        self.after(100, self._drain_uiq)

    # -------- UI --------
    def _build_ui(self):
        self.style.configure("TLabel", font=("Segoe UI", 10))
        self.style.configure("Title.TLabel", font=("Segoe UI", 14, "bold"))

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
        self.file_btn = tb.Button(local_row, text="", command=self._pick_local_file, bootstyle="secondary")
        self.file_btn.pack(side="left", padx=4)
        self.folder_btn = tb.Button(local_row, text="", command=self._pick_local_folder, bootstyle="secondary")
        self.folder_btn.pack(side="left")

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

        options_row = tb.Frame(self.transcribe_frame)
        options_row.pack(fill="x", pady=(8, 0))
        self.clean_text_chk = tb.Checkbutton(options_row, text="", variable=self.clean_text)
        self.clean_text_chk.pack(anchor="w")
        self.export_md_chk = tb.Checkbutton(options_row, text="", variable=self.export_md)
        self.export_md_chk.pack(anchor="w")

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
        self.step_bar = tb.Progressbar(row_p2, mode="determinate", length=600)
        self.step_bar.pack(side="left", fill="x", expand=True)

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

    def _on_output_mode_changed(self):
        selected_label = self.output_mode.get()
        self.output_mode_key.set(output_mode_label_to_key(selected_label))
        self._update_mode_ui()

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
            self.local_path.set(f)

    def _pick_local_folder(self):
        d = filedialog.askdirectory(title=self.t("folderpicker.title"))
        if d:
            self.local_path.set(d)

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
        self._save_current_config()
        self.stop_flag.clear()
        self.start_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")
        self.q_status(self.t("status.running"))

        # Collect settings
        is_playlist = (self.yt_mode.get() == "playlist")
        url = self.url.get().strip() if is_youtube else None
        local_path = Path(self.local_path.get().strip()) if not is_youtube else None
        prefer_gpu = bool(self.use_gpu.get())
        model = self.model.get()
        src_language = self.source_lang.get().strip() or "auto"
        target_lang = self.target_lang.get()
        translation_model = TRANSLATION_MODELS.get(target_lang, TRANSLATION_MODELS["en→cs"])
        lang_code = LANG_CODES.get(target_lang, "ces")
        batch = int(self.batch.get())
        sub_mode = self.subtitle_mode.get()
        quality = self.video_quality.get()
        clean_text = bool(self.clean_text.get())
        export_md = bool(self.export_md.get())
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
            try:
                if self.auto_model.get():
                    if prefer_gpu:
                        self.q_step(t_local("step.detect_gpu"))
                        self.q_step_indeterminate(True)
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
                transcripts_dir = final_base_dir / "transcripts"
                subtitles_src_final_dir = final_base_dir / "subtitles_source"
                subtitles_trans_final_dir = final_base_dir / "subtitles_translated"
                videos_final_dir = final_base_dir / "videos"
                originals_dir = final_base_dir / "originals"
                for d in [transcripts_dir, subtitles_src_final_dir, subtitles_trans_final_dir, videos_final_dir, originals_dir]:
                    d.mkdir(parents=True, exist_ok=True)

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
                    self.q_step(t_local("step.download"))
                    self.q_step_progress(0)
                    download_single_or_playlist(url, workdir, is_playlist, quality,
                                                 self.q_log, self.q_step_progress, self.stop_flag)
                    downloaded_files = list_videos(downloads_dir)
                    videos = downloaded_files
                else:
                    if not local_path:
                        raise RuntimeError(t_local("error.enter_path"))
                    if local_path.is_file():
                        videos = [local_path]
                    else:
                        videos = list_videos(local_path)

                if not videos:
                    raise RuntimeError(t_local("error.no_media"))

                if output_mode == "video_subs" and any(is_audio_file(v) for v in videos):
                    raise RuntimeError(t_local("error.audio_not_supported"))

                translation_needed = output_mode in {"video_subs", "srt_only"}
                lang_suffix = target_lang.split("→")[1] if translation_needed else ""
                self.total_videos = len(videos)
                self.done_videos = 0
                self.q_overall(self.done_videos, self.total_videos)

                for i, v in enumerate(videos, 1):
                    if self.stop_flag.is_set():
                        raise RuntimeError(t_local("error.stopped"))

                    self.q_status(f"{t_local('status.processing')} {i}/{len(videos)}: {v.name}")
                    base_name = timestamped_base_name(v)

                    # Transcribe
                    self.q_step(t_local("step.transcribe"))
                    self.q_step_progress(0)
                    transcription = faster_whisper_transcribe(
                        v, srt_src_dir, model, prefer_gpu, src_language,
                        self.q_log, self.q_step_progress, self.stop_flag
                    )

                    # Export transcripts (.txt and optional .md)
                    self.q_step(t_local("step.export_txt"))
                    self.q_step_indeterminate(True)
                    export_transcripts(v, transcription, transcripts_dir, clean_text, export_md, split_minutes, self.q_log)
                    self.q_step_indeterminate(False)

                    if not translation_needed:
                        self.done_videos += 1
                        self.q_overall(self.done_videos, self.total_videos)
                        continue

                    # Translate
                    self.q_step(f"{t_local('step.translate')} ({target_lang})")
                    self.q_step_progress(0)
                    srt_trans_tmp = srt_trans_dir / f"{sanitize_filename(v.stem)}.{lang_suffix}.srt"
                    translate_srt(transcription.srt_path, srt_trans_tmp, translation_model, prefer_gpu, batch,
                                  self.q_log, self.q_step_progress, self.stop_flag)

                    # Export SRTs to final folders
                    self.q_step(t_local("step.export_srt"))
                    self.q_step_indeterminate(True)
                    final_srt_src = unique_path(subtitles_src_final_dir, f"{base_name}.{transcription.detected_lang}.srt")
                    final_srt_trans = unique_path(subtitles_trans_final_dir, f"{base_name}.{lang_suffix}.srt")
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
                    self.q_step(t_local("step.embed"))
                    suffix = ".soft" if sub_mode == "soft" else ".hard"
                    out_mp4 = unique_path(videos_final_dir, f"{base_name}{suffix}.sub.mp4")
                    if sub_mode == "soft":
                        soft_subtitles(v, srt_trans_tmp, out_mp4, lang_code,
                                       self.q_log, self.q_step_indeterminate, self.stop_flag)
                    else:
                        hard_subtitles(v, srt_trans_tmp, out_mp4,
                                       sub_font, sub_size, sub_color, sub_outline_color, sub_outline_width,
                                       self.q_log, self.q_step_indeterminate, self.stop_flag)
                    self.q_log(f"[OK] Saved: {out_mp4}\n")

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
        self.output_lbl.configure(text=self.t("source.output"))
        self.pick_out_btn.configure(text=self.t("source.pick"))
        self.mode_lbl.configure(text=self.t("source.mode"))
        self.keep_originals_chk.configure(text=self.t("source.keep_originals"))

        self.transcribe_frame.configure(text=self.t("transcribe.title"))
        self.speech_lang_lbl.configure(text=self.t("transcribe.speech_language"))
        self.clean_text_chk.configure(text=self.t("transcribe.clean_text"))
        self.export_md_chk.configure(text=self.t("transcribe.export_md"))
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

        self.prog_frame.configure(text=self.t("progress.title"))
        self.overall_lbl_title.configure(text=self.t("progress.total"))
        self.activity_lbl_title.configure(text=self.t("progress.activity"))
        self.status_lbl.configure(text=self.t("status.ready"))

        labels = output_mode_labels(self.lang.get())
        self.output_combo.configure(values=labels)
        current_key = normalize_output_mode(self.output_mode_key.get())
        self.output_mode_key.set(current_key)
        table = I18N.get(self.lang.get(), I18N["en"])
        self.output_mode.set(table.get(f"output_mode.{current_key}", current_key))

        quick_labels = quick_model_labels(self.lang.get())
        self.quick_model_cb.configure(values=quick_labels)
        current_quick_key = normalize_quick_model(self.quick_model_key.get())
        self.quick_model_key.set(current_quick_key)
        self._sync_quick_model_label()

        self._update_mode_ui()
        if self._gpu_info_cache:
            self._apply_gpu_info(self._gpu_info_cache)
        else:
            self.gpu_badge.configure(text=self.t("performance.gpu_idle"), bootstyle="secondary")
            self._apply_auto_model()
        self._save_current_config()
