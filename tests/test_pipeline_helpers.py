from threading import Event
from threading import Timer
import asyncio
import sys
import types

import pytest

from transcribemate.core.cancellation import OperationCancelledError
from transcribemate.pipeline.download import quality_to_format
from transcribemate.pipeline.subtitles import hex_to_ass_color
from transcribemate.pipeline.transcribe import format_timestamp
from transcribemate.pipeline.translate import _split_speaker_prefix


def test_quality_to_format_best():
    fmt = quality_to_format("best")
    assert "bestvideo" in fmt


def test_quality_to_format_height():
    fmt = quality_to_format("720p")
    assert "height<=720" in fmt


def test_quality_to_format_best_without_merge_prefers_single_file():
    fmt = quality_to_format("best", allow_merge=False)

    assert "bestvideo" not in fmt
    assert "[acodec!=none]" in fmt


def test_quality_to_format_audio_only_prefers_audio_streams():
    fmt = quality_to_format("best", allow_merge=False, audio_only=True)

    assert "bestaudio" in fmt
    assert "bestvideo" not in fmt


def test_hex_to_ass_color_rgb_order():
    assert hex_to_ass_color("#112233") == "&H332211&"


def test_format_timestamp_zero():
    assert format_timestamp(0.0) == "00:00:00,000"


def test_split_speaker_prefix_parses_prefix_and_body():
    prefix, body = _split_speaker_prefix("Alice: Hello there")
    assert prefix == "Alice: "
    assert body == "Hello there"


def test_translation_cancellation_raises_operation_cancelled(monkeypatch, tmp_path):
    stop_flag = Event()
    stop_flag.set()

    class FakeTokenizer:
        @classmethod
        def from_pretrained(cls, _model_name):
            raise AssertionError("cancelled translation should stop during model preparation")

    torch = types.ModuleType("torch")
    torch.cuda = types.SimpleNamespace(is_available=lambda: False)
    transformers = types.ModuleType("transformers")
    transformers.AutoTokenizer = FakeTokenizer
    transformers.AutoModelForSeq2SeqLM = object
    monkeypatch.setitem(sys.modules, "torch", torch)
    monkeypatch.setitem(sys.modules, "transformers", transformers)

    from transcribemate.pipeline.translate import translate_srt

    class ProgressContext:
        def __init__(self, callback):
            self.callback = callback

        def __enter__(self):
            self.callback("model", 0.0)

        def __exit__(self, *_args):
            return False

    monkeypatch.setattr(
        "transcribemate.pipeline.translate.huggingface_download_progress",
        lambda callback: ProgressContext(callback),
    )

    source = tmp_path / "source.srt"
    source.write_text("1\n00:00:00,000 --> 00:00:01,000\nHello\n", encoding="utf-8")
    with pytest.raises(OperationCancelledError):
        translate_srt(source, tmp_path / "translated.srt", "fake-model", False, 1, lambda _: None, lambda _: None, stop_flag)


def test_huggingface_progress_does_not_swallow_cancellation(monkeypatch):
    import importlib
    from transcribemate.core.hf_progress import huggingface_download_progress

    real_import_module = importlib.import_module
    owners = {}

    class FakeTqdm:
        def __init__(self, *args, **kwargs):
            self.total = kwargs.get("total")
            self.n = 0

        def update(self, amount=1):
            self.n += amount

        def close(self):
            pass

    def import_module(name):
        if name.startswith("huggingface_hub"):
            return owners.setdefault(name, types.SimpleNamespace(__name__=name, tqdm=FakeTqdm))
        return real_import_module(name)

    tqdm = types.ModuleType("tqdm")
    tqdm_auto = types.ModuleType("tqdm.auto")
    tqdm_auto.tqdm = FakeTqdm
    monkeypatch.setitem(sys.modules, "tqdm", tqdm)
    monkeypatch.setitem(sys.modules, "tqdm.auto", tqdm_auto)
    monkeypatch.setattr("transcribemate.core.hf_progress.importlib.import_module", import_module)

    with pytest.raises(OperationCancelledError):
        with huggingface_download_progress(lambda *_args: (_ for _ in ()).throw(OperationCancelledError("cancel"))):
            owners["huggingface_hub.utils.tqdm"].tqdm(total=100, desc="weights")


def test_transcription_cancellation_raises_operation_cancelled(monkeypatch, tmp_path):
    from types import SimpleNamespace
    from transcribemate.pipeline import transcribe

    stop_flag = Event()
    stop_flag.set()

    class Model:
        def transcribe(self, *_args, **_kwargs):
            return iter([SimpleNamespace(start=0.0, end=1.0, text="hello")]), SimpleNamespace(language="en", duration=1.0)

    monkeypatch.setattr(transcribe, "_resolve_whisper_model_path", lambda *_args: "model")
    monkeypatch.setattr(transcribe, "_load_whisper_model", lambda *_args: (Model(), "cpu", "int8"))

    with pytest.raises(OperationCancelledError):
        transcribe.faster_whisper_transcribe(
            tmp_path / "input.wav", tmp_path / "output", "fake", False, "en", lambda _: None, lambda _: None, stop_flag
        )


def test_download_hook_cancellation_raises_operation_cancelled(monkeypatch, tmp_path):
    from transcribemate.pipeline.download import download_single_or_playlist

    stop_flag = Event()

    class DownloadError(Exception):
        pass

    class YoutubeDL:
        def __init__(self, options):
            self.options = options

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def download(self, _urls):
            stop_flag.set()
            self.options["progress_hooks"][0]({"status": "downloading", "total_bytes": 10, "downloaded_bytes": 1})

    yt_dlp = types.ModuleType("yt_dlp")
    yt_dlp.YoutubeDL = YoutubeDL
    utils = types.ModuleType("yt_dlp.utils")
    utils.DownloadError = DownloadError
    monkeypatch.setitem(sys.modules, "yt_dlp", yt_dlp)
    monkeypatch.setitem(sys.modules, "yt_dlp.utils", utils)
    monkeypatch.setattr("transcribemate.pipeline.download.ffmpeg_path", lambda: None)
    with pytest.raises(OperationCancelledError):
        download_single_or_playlist(
            "https://www.youtube.com/watch?v=test123", tmp_path, False, "best", "txt_only", lambda _: None, lambda _: None, stop_flag
        )


def test_subtitle_helper_propagates_subprocess_cancellation(monkeypatch, tmp_path):
    import sys
    from transcribemate.pipeline import subtitles

    monkeypatch.setattr(subtitles, "ffmpeg_path", lambda: sys.executable)
    stop_flag = Event()
    stop_flag.set()
    indeterminate = []

    with pytest.raises(OperationCancelledError):
        subtitles.hard_subtitles(
            tmp_path / "video.mp4", tmp_path / "source.srt", tmp_path / "output.mp4",
            "Arial", 24, "#FFFFFF", "#000000", 1, lambda _: None,
            indeterminate.append, stop_flag,
        )

    assert indeterminate == [True, False]


def test_dubbing_tts_cancellation_interrupts_async_child(monkeypatch, tmp_path):
    from transcribemate.pipeline.dubbing import _synthesize_chunks_async

    started = Event()

    class Communicate:
        def __init__(self, *_args, **_kwargs):
            pass

        async def save(self, _path):
            started.set()
            await asyncio.sleep(30)

    edge_tts = types.ModuleType("edge_tts")
    edge_tts.Communicate = Communicate
    monkeypatch.setitem(sys.modules, "edge_tts", edge_tts)
    stop_flag = Event()
    timer = Timer(0.05, stop_flag.set)
    timer.start()
    try:
        with pytest.raises(OperationCancelledError):
            asyncio.run(
                _synthesize_chunks_async(
                    text_chunks=["hello"], voice="test", out_dir=tmp_path, log=lambda _: None,
                    stop_flag=stop_flag, set_step_progress=lambda _: None,
                )
            )
    finally:
        timer.cancel()
    assert started.is_set()
