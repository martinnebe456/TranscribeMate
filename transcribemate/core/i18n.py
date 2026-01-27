"""Localization, labels, and user-facing constants."""

from typing import Dict, List

from .version import APP_VERSION

# -----------------------------
# Translation models (Helsinki-NLP)
# -----------------------------
TRANSLATION_MODELS: Dict[str, str] = {
    "en→cs": "Helsinki-NLP/opus-mt-en-cs",
    "en→sk": "Helsinki-NLP/opus-mt-en-sk",
    "en→de": "Helsinki-NLP/opus-mt-en-de",
    "en→pl": "Helsinki-NLP/opus-mt-en-pl",
    "en→fr": "Helsinki-NLP/opus-mt-en-fr",
    "en→es": "Helsinki-NLP/opus-mt-en-es",
    "en→it": "Helsinki-NLP/opus-mt-en-it",
    "en→ru": "Helsinki-NLP/opus-mt-en-ru",
    "en→uk": "Helsinki-NLP/opus-mt-en-uk",
    "en→pt": "Helsinki-NLP/opus-mt-en-pt",
}

LANG_CODES: Dict[str, str] = {
    "en→cs": "ces",
    "en→sk": "slk",
    "en→de": "deu",
    "en→pl": "pol",
    "en→fr": "fra",
    "en→es": "spa",
    "en→it": "ita",
    "en→ru": "rus",
    "en→uk": "ukr",
    "en→pt": "por",
}

VIDEO_QUALITIES = ["best", "1080p", "720p", "480p", "360p"]
APP_NAME = "TranscribeMate"
APP_NAME_VERSION = f"{APP_NAME} {APP_VERSION}".strip()
LANGUAGES = ["en", "cs"]
OUTPUT_MODE_KEYS = ["conference", "video_subs", "srt_only", "txt_only"]
QUICK_MODEL_KEYS = ["small", "medium", "quality"]
QUICK_MODEL_MAP = {
    "small": "small",
    "medium": "medium",
    "quality": "large-v3",
}
THEME_KEYS = ["light", "dark", "dracula"]
THEME_MAP = {
    "light": "flatly",
    "dark": "darkly",
    "dracula": "vapor",
}
LEGACY_THEME_MAP = {
    "light": "light",
    "dark": "dark",
    "dracula": "dracula",
    "svetly": "light",
    "světlý": "light",
    "tmavy": "dark",
    "tmavý": "dark",
}
LEGACY_QUICK_MODEL_MAP = {
    "malý": "small",
    "střední": "medium",
    "kvalitní": "quality",
    "small": "small",
    "medium": "medium",
    "quality": "quality",
}
CLEAN_PAUSE_SECONDS = 2.0
SOURCE_LANGUAGES = ["auto", "en", "cs", "sk", "de", "pl", "fr", "es", "it", "ru", "uk", "pt", "ja", "ko", "zh"]
SUMMARY_LANG_KEYS = SOURCE_LANGUAGES

VIDEO_EXTS = {".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".flv"}
AUDIO_EXTS = {".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wma"}

LEGACY_OUTPUT_MODE_MAP = {
    "video+titulky": "video_subs",
    "pouze SRT": "srt_only",
    "pouze přepis (.txt)": "txt_only",
    "konference": "conference",
    "Konference": "conference",
    "video+subtitles": "video_subs",
    "SRT only": "srt_only",
    "transcript only (.txt)": "txt_only",
    "Conference": "conference",
    "conference": "conference",
}

I18N: Dict[str, Dict[str, str]] = {
    "en": {
        "app.title": f"{APP_NAME_VERSION} — Audio/Video → Transcript / Subtitles",
        "header.title": f"{APP_NAME_VERSION} — Audio/Video → Transcript / Subtitles",
        "header.language": "Language",
        "header.theme": "Theme",
        "source.title": "Source (drop a file or URL)",
        "source.youtube": "YouTube",
        "source.local": "Local file/folder",
        "source.playlist": "Playlist",
        "source.single": "Single video",
        "source.quality": "Quality:",
        "source.url": "URL",
        "source.path": "Path",
        "source.file": "File…",
        "source.folder": "Folder…",
        "source.prefix": "Prefix",
        "source.prefix_hint": "Added to output filenames",
        "source.files_hint_empty": "Drop files here. Double-click to open.",
        "source.files_hint_count": "Selected files: {count}. Double-click to open.",
        "conference.meta.title": "Conference metadata",
        "conference.meta.speaker": "Speaker",
        "conference.meta.topic": "Topic",
        "source.output": "Output",
        "source.pick": "Browse…",
        "source.mode": "Mode:",
        "source.keep_originals": "Keep downloaded originals",
        "mode.hint.prefix": "Mode:",
        "mode.hint.conference": "Conference mode: pick a folder and export transcripts, summary prompts, and Confluence templates.",
        "mode.hint.video_subs": "Creates a transcript, translates subtitles, and embeds them into the video.",
        "mode.hint.srt_only": "Saves original and translated subtitles (.srt) without creating a video.",
        "mode.hint.txt_only": "Saves a clean speech transcript to .txt (no translation, no subtitles).",
        "transcribe.title": "Transcription",
        "transcribe.speech_language": "Speech language",
        "transcribe.summary_lang": "Summary output language",
        "transcribe.clean_text": "Clean text export (dedupe + paragraphs)",
        "transcribe.export_md": "Also export Markdown (.md)",
        "transcribe.summary_pack": "Generate summary pack (ChatGPT + Confluence)",
        "transcribe.notify_done": "Notify & open output on completion",
        "transcribe.split_minutes": "Split every (minutes, 0=off)",
        "summary_lang.auto": "auto (detected)",
        "translate.title": "Translation",
        "translate.target_language": "Target language",
        "translate.batch": "Batch",
        "subs.title": "Subtitles",
        "subs.soft": "Soft (toggleable)",
        "subs.hard": "Hard (burned-in)",
        "subs.font": "Font:",
        "subs.size": "Size:",
        "subs.color": "Color:",
        "subs.outline": "Outline:",
        "subs.width": "Width:",
        "performance.title": "Performance",
        "performance.gpu": "GPU (CUDA)",
        "performance.detect": "Detect",
        "performance.auto_model": "Auto model",
        "performance.model": "Model",
        "performance.quick_model": "Quick model",
        "performance.gpu_unavailable": "GPU: CUDA not available",
        "performance.gpu_detecting": "GPU: detecting...",
        "performance.gpu_idle": "GPU: not checked (click Detect)",
        "actions.start": "Start",
        "actions.stop": "Stop",
        "actions.update_models": "Refresh model cache",
        "actions.open_output": "Open output folder",
        "actions.open_data": "Open app data folder",
        "actions.show_log": "Show log",
        "system.title": "System usage",
        "system.cpu": "CPU",
        "system.ram": "RAM",
        "system.gpu": "GPU",
        "system.na": "N/A",
        "progress.title": "Progress",
        "progress.total": "Overall",
        "progress.activity": "Activity",
        "progress.eta": "ETA",
        "status.ready": "Ready",
        "status.running": "Running…",
        "status.stopping": "Stopping…",
        "status.stop": "Stopped",
        "status.error": "Error",
        "status.done": "Done. Output:",
        "status.processing": "Processing",
        "dialog.error": "Error",
        "dialog.update_models.title": "Refresh models",
        "dialog.update_models.body": "This will delete the model cache. Models will be downloaded again on the next run.\nContinue?",
        "dialog.update_models.done": "Cache cleared. Models will be downloaded again on the next run.",
        "dialog.ok": "OK",
        "filepicker.title": "Select audio/video file",
        "filepicker.media": "Audio/Video files",
        "filepicker.all": "All files",
        "folderpicker.title": "Select folder with media",
        "colorpicker.subs": "Subtitle color",
        "colorpicker.outline": "Outline color",
        "step.download": "Downloading",
        "step.detect_gpu": "Detecting GPU (first run may take a while)",
        "step.prepare_outputs": "Preparing output folders",
        "step.scan_media": "Scanning media files",
        "step.transcribe": "Transcription (faster-whisper)",
        "step.translate": "Translation",
        "step.embed": "Embedding subtitles",
        "step.export_srt": "Export SRT",
        "step.export_txt": "Export transcript (.txt)",
        "step.export_with_summary": "Export transcript + summary pack",
        "step.done": "Done",
        "error.already_running": "Processing is already running.",
        "error.out_dir_missing": "Output folder does not exist.",
        "error.enter_url": "Enter a URL.",
        "error.invalid_url": "Invalid YouTube URL.",
        "error.enter_path": "Enter a file or folder path.",
        "error.path_missing": "File or folder does not exist.",
        "error.no_media": "No media files were found.",
        "error.conference_requires_folder": "Conference mode requires a folder with media files.",
        "error.audio_not_supported": "Mode 'video+subtitles' does not support audio-only files. Please use 'transcript only (.txt)' or 'SRT only'.",
        "error.stopped": "Zastaveno uživatelem.",
        "output_mode.conference": "conference (.txt/.md only)",
        "output_mode.video_subs": "video+subtitles",
        "output_mode.srt_only": "SRT only",
        "output_mode.txt_only": "transcript only (.txt)",
        "quick_model.small": "Small",
        "quick_model.medium": "Medium",
        "quick_model.quality": "Quality",
        "theme.light": "Light",
        "theme.dark": "Dark",
        "theme.dracula": "Dracula",
        "notify.error_title": "Error",
        "notify.done_title": "Processing complete",
        "notify.done_body": "Processed {done}/{total} items.",
    },
    "cs": {
        "app.title": f"{APP_NAME_VERSION} — Audio/Video → Přepis / Titulky",
        "header.title": f"{APP_NAME_VERSION} — Audio/Video → Přepis / Titulky",
        "header.language": "Jazyk",
        "header.theme": "Motiv",
        "source.title": "Zdroj (přetáhni soubor nebo URL)",
        "source.youtube": "YouTube",
        "source.local": "Lokální soubor/složka",
        "source.playlist": "Playlist",
        "source.single": "Jen video",
        "source.quality": "Kvalita:",
        "source.url": "URL",
        "source.path": "Cesta",
        "source.file": "Soubor…",
        "source.folder": "Složka…",
        "source.prefix": "Prefix",
        "source.prefix_hint": "Přidá se do názvu výstupů",
        "source.files_hint_empty": "Přetáhni sem soubory. Dvojklikem otevřeš.",
        "source.files_hint_count": "Vybrané soubory: {count}. Dvojklikem otevřeš.",
        "conference.meta.title": "Konferenční metadata",
        "conference.meta.speaker": "Mluvčí",
        "conference.meta.topic": "Téma",
        "source.output": "Výstup",
        "source.pick": "Vybrat…",
        "source.mode": "Režim:",
        "source.keep_originals": "Zachovat stažené originály",
        "mode.hint.prefix": "Režim:",
        "mode.hint.conference": "Režim Konference: vyber složku a ulož přepisy, summary prompty a Confluence šablony.",
        "mode.hint.video_subs": "Vytvoří přepis, přeloží titulky a vloží je do videa.",
        "mode.hint.srt_only": "Uloží originální i přeložené titulky (.srt) bez vytváření videa.",
        "mode.hint.txt_only": "Uloží čistý přepis řeči do .txt (bez překladu a titulků).",
        "transcribe.title": "Přepis",
        "transcribe.speech_language": "Jazyk řeči",
        "transcribe.summary_lang": "Jazyk summary výstupu",
        "transcribe.clean_text": "Clean text export (bez duplicit + odstavce)",
        "transcribe.export_md": "Exportovat i Markdown (.md)",
        "transcribe.summary_pack": "Vytvořit summary pack (ChatGPT + Confluence)",
        "transcribe.notify_done": "Po dokončení upozornit a otevřít výstup",
        "transcribe.split_minutes": "Rozdělit po (minuty, 0=vypnuto)",
        "summary_lang.auto": "auto (detekovaný)",
        "translate.title": "Překlad",
        "translate.target_language": "Cílový jazyk",
        "translate.batch": "Batch",
        "subs.title": "Titulky",
        "subs.soft": "Soft (vypínatelné)",
        "subs.hard": "Hard (vypálené)",
        "subs.font": "Font:",
        "subs.size": "Velikost:",
        "subs.color": "Barva:",
        "subs.outline": "Obrys:",
        "subs.width": "Šířka:",
        "performance.title": "Výkon",
        "performance.gpu": "GPU (CUDA)",
        "performance.detect": "Detekovat",
        "performance.auto_model": "Auto model",
        "performance.model": "Model",
        "performance.quick_model": "Rychlá volba",
        "performance.gpu_unavailable": "GPU: CUDA nedostupné",
        "performance.gpu_detecting": "GPU: detekuji...",
        "performance.gpu_idle": "GPU: nezjištěno (klikni Detekovat)",
        "actions.start": "Start",
        "actions.stop": "Stop",
        "actions.update_models": "Update modelů",
        "actions.open_output": "Otevřít výstupní složku",
        "actions.open_data": "Otevřít složku aplikace",
        "actions.show_log": "Zobrazit log",
        "system.title": "Využití systému",
        "system.cpu": "CPU",
        "system.ram": "RAM",
        "system.gpu": "GPU",
        "system.na": "N/A",
        "progress.title": "Postup",
        "progress.total": "Celkem",
        "progress.activity": "Aktivita",
        "progress.eta": "ETA",
        "status.ready": "Připraveno",
        "status.running": "Běží…",
        "status.stopping": "Zastavuji…",
        "status.stop": "Zastaveno",
        "status.error": "Chyba",
        "status.done": "Hotovo. Výstup:",
        "status.processing": "Zpracovávám",
        "dialog.error": "Chyba",
        "dialog.update_models.title": "Update modelů",
        "dialog.update_models.body": "Smaže cache modelů. Při dalším běhu se modely znovu stáhnou.\nPokračovat?",
        "dialog.update_models.done": "Cache smazána. Modely se stáhnou při dalším běhu.",
        "dialog.ok": "OK",
        "filepicker.title": "Vyber audio/video soubor",
        "filepicker.media": "Audio/Video soubory",
        "filepicker.all": "Všechny soubory",
        "folderpicker.title": "Vyber složku s médii",
        "colorpicker.subs": "Barva titulků",
        "colorpicker.outline": "Barva obrysu",
        "step.download": "Stahování",
        "step.detect_gpu": "Detekuji GPU (první spuštění může chvíli trvat)",
        "step.prepare_outputs": "Připravuji výstupní složky",
        "step.scan_media": "Procházím média",
        "step.transcribe": "Přepis (faster-whisper)",
        "step.translate": "Překlad",
        "step.embed": "Vložení titulků",
        "step.export_srt": "Export SRT",
        "step.export_txt": "Export přepisu (.txt)",
        "step.export_with_summary": "Export přepisu + summary pack",
        "step.done": "Hotovo",
        "error.already_running": "Zpracování již běží.",
        "error.out_dir_missing": "Výstupní složka neexistuje.",
        "error.enter_url": "Zadej URL.",
        "error.invalid_url": "Neplatná YouTube URL.",
        "error.enter_path": "Zadej cestu k souboru nebo složce.",
        "error.path_missing": "Soubor nebo složka neexistuje.",
        "error.no_media": "Nebyla nalezena žádná média.",
        "error.conference_requires_folder": "Režim Konference vyžaduje složku s médii.",
        "error.audio_not_supported": "Režim 'video+titulky' nepodporuje čisté audio soubory. Zvol prosím 'pouze přepis (.txt)' nebo 'pouze SRT'.",
        "error.stopped": "Stopped by user.",
        "output_mode.conference": "konference (jen .txt/.md)",
        "output_mode.video_subs": "video+titulky",
        "output_mode.srt_only": "pouze SRT",
        "output_mode.txt_only": "pouze přepis (.txt)",
        "quick_model.small": "Malý",
        "quick_model.medium": "Střední",
        "quick_model.quality": "Kvalitní",
        "theme.light": "Světlý",
        "theme.dark": "Tmavý",
        "theme.dracula": "Dracula",
        "notify.error_title": "Chyba",
        "notify.done_title": "Zpracování dokončeno",
        "notify.done_body": "Zpracováno {done}/{total} položek.",
    },
}

def normalize_output_mode(value: str) -> str:
    if value in OUTPUT_MODE_KEYS:
        return value
    return LEGACY_OUTPUT_MODE_MAP.get(value, "video_subs")


def output_mode_labels(lang: str) -> List[str]:
    table = I18N.get(lang, I18N["en"])
    return [table.get(f"output_mode.{k}", k) for k in OUTPUT_MODE_KEYS]


def output_mode_label_to_key(label: str) -> str:
    for lang in LANGUAGES:
        table = I18N.get(lang, {})
        for key in OUTPUT_MODE_KEYS:
            if label == table.get(f"output_mode.{key}"):
                return key
    return LEGACY_OUTPUT_MODE_MAP.get(label, "video_subs")


def normalize_summary_lang(value: str) -> str:
    if value in SUMMARY_LANG_KEYS:
        return value
    lowered = value.strip().lower() if isinstance(value, str) else ""
    if lowered in SUMMARY_LANG_KEYS:
        return lowered
    return "auto"


def summary_lang_labels(lang: str) -> List[str]:
    table = I18N.get(lang, I18N["en"])
    labels: List[str] = []
    for key in SUMMARY_LANG_KEYS:
        if key == "auto":
            labels.append(table.get("summary_lang.auto", "auto"))
        else:
            labels.append(key)
    return labels


def summary_lang_label_to_key(label: str) -> str:
    for lang in LANGUAGES:
        table = I18N.get(lang, {})
        if label == table.get("summary_lang.auto"):
            return "auto"
    if label in SUMMARY_LANG_KEYS:
        return label
    return "auto"


def summary_lang_label_for_key(key: str, lang: str) -> str:
    norm_key = normalize_summary_lang(key)
    if norm_key == "auto":
        table = I18N.get(lang, I18N["en"])
        return table.get("summary_lang.auto", "auto")
    return norm_key


def normalize_quick_model(value: str) -> str:
    if value in QUICK_MODEL_KEYS:
        return value
    return LEGACY_QUICK_MODEL_MAP.get(value, "medium")


def quick_model_labels(lang: str) -> List[str]:
    table = I18N.get(lang, I18N["en"])
    return [table.get(f"quick_model.{k}", k) for k in QUICK_MODEL_KEYS]


def quick_model_label_to_key(label: str) -> str:
    for lang in LANGUAGES:
        table = I18N.get(lang, {})
        for key in QUICK_MODEL_KEYS:
            if label == table.get(f"quick_model.{key}"):
                return key
    return normalize_quick_model(label)


def quick_model_key_for_model(model_name: str) -> str:
    for key, mapped in QUICK_MODEL_MAP.items():
        if model_name == mapped:
            return key
    return "medium"



def normalize_theme(value: str) -> str:
    if value in THEME_KEYS:
        return value
    lowered = value.strip().lower() if isinstance(value, str) else ""
    if lowered in THEME_KEYS:
        return lowered
    return LEGACY_THEME_MAP.get(lowered, "light")


def theme_labels(lang: str) -> List[str]:
    table = I18N.get(lang, I18N["en"])
    return [table.get(f"theme.{k}", k) for k in THEME_KEYS]


def theme_label_to_key(label: str) -> str:
    for lang in LANGUAGES:
        table = I18N.get(lang, {})
        for key in THEME_KEYS:
            if label == table.get(f"theme.{key}"):
                return key
    return normalize_theme(label)


def theme_name_for_key(key: str) -> str:
    norm_key = normalize_theme(key)
    return THEME_MAP.get(norm_key, THEME_MAP["light"])
