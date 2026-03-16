from __future__ import annotations

import json
import re
import unicodedata
from collections import Counter
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any

from pypdf import PdfReader


@dataclass(slots=True)
class FixtureValidationResult:
    fixture_id: str
    source_file: str
    transcript_path: str
    summary_path: str
    similarity_score: float
    char_ratio: float
    token_f1: float
    required_similarity: float
    summary_headings: list[str]
    summary_char_count: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "fixture_id": self.fixture_id,
            "source_file": self.source_file,
            "transcript_path": self.transcript_path,
            "summary_path": self.summary_path,
            "similarity_score": round(self.similarity_score, 4),
            "char_ratio": round(self.char_ratio, 4),
            "token_f1": round(self.token_f1, 4),
            "required_similarity": round(self.required_similarity, 4),
            "summary_headings": list(self.summary_headings),
            "summary_char_count": int(self.summary_char_count),
        }


def load_manifest(manifest_path: Path) -> dict[str, Any]:
    payload = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise RuntimeError(f"Invalid fixture manifest: {manifest_path}")
    fixtures = payload.get("fixtures")
    if not isinstance(fixtures, list) or not fixtures:
        raise RuntimeError(f"Fixture manifest does not contain any fixtures: {manifest_path}")
    return payload


def extract_pdf_text(pdf_path: Path) -> str:
    reader = PdfReader(str(pdf_path))
    parts: list[str] = []
    for page in reader.pages:
        parts.append(page.extract_text() or "")
    return "\n".join(parts).strip()


def normalize_text(value: str) -> str:
    text = unicodedata.normalize("NFKC", str(value or ""))
    text = text.lower()
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"[^\w\s]", " ", text, flags=re.UNICODE)
    text = re.sub(r"\b\d{1,2}:\d{2}(?::\d{2})?\b", " ", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def token_f1_score(reference_text: str, candidate_text: str) -> float:
    reference_tokens = normalize_text(reference_text).split()
    candidate_tokens = normalize_text(candidate_text).split()
    if not reference_tokens or not candidate_tokens:
        return 0.0

    reference_counts = Counter(reference_tokens)
    candidate_counts = Counter(candidate_tokens)
    overlap = reference_counts & candidate_counts
    overlap_count = sum(overlap.values())
    if overlap_count <= 0:
        return 0.0

    precision = overlap_count / float(sum(candidate_counts.values()))
    recall = overlap_count / float(sum(reference_counts.values()))
    if precision + recall == 0.0:
        return 0.0
    return (2.0 * precision * recall) / (precision + recall)


def similarity_metrics(reference_text: str, candidate_text: str) -> dict[str, float]:
    normalized_reference = normalize_text(reference_text)
    normalized_candidate = normalize_text(candidate_text)
    char_ratio = SequenceMatcher(None, normalized_reference, normalized_candidate).ratio()
    token_f1 = token_f1_score(normalized_reference, normalized_candidate)
    return {
        "char_ratio": float(char_ratio),
        "token_f1": float(token_f1),
        "score": float(max(char_ratio, token_f1)),
    }


def _find_latest_matching_file(output_root: Path, *, stem_hint: str, suffix_predicate) -> Path:
    candidates: list[Path] = []
    stem_hint_lower = stem_hint.lower()
    for path in output_root.rglob("*"):
        if not path.is_file():
            continue
        lower_name = path.name.lower()
        if stem_hint_lower not in lower_name:
            continue
        if suffix_predicate(path):
            candidates.append(path)

    if not candidates:
        raise RuntimeError(f"No generated artifact found for '{stem_hint}' under {output_root}")

    candidates.sort(key=lambda item: item.stat().st_mtime, reverse=True)
    return candidates[0]


def validate_output_tree(
    *,
    output_root: Path,
    fixtures_dir: Path,
    manifest_path: Path,
    minimum_summary_chars: int = 80,
) -> dict[str, Any]:
    output_root = Path(output_root).expanduser().resolve()
    fixtures_dir = Path(fixtures_dir).expanduser().resolve()
    manifest = load_manifest(manifest_path)
    required_headings = [str(item) for item in manifest.get("summary_required_headings") or []]

    results: list[FixtureValidationResult] = []
    for fixture in manifest["fixtures"]:
        fixture_id = str(fixture.get("id") or "").strip()
        source_name = str(fixture.get("source") or "").strip()
        reference_name = str(fixture.get("reference_pdf") or "").strip()
        summary_lang = str(fixture.get("summary_lang") or "auto").strip().lower() or "auto"
        similarity_min = float(fixture.get("transcript_similarity_min") or 0.0)
        if not fixture_id or not source_name or not reference_name:
            raise RuntimeError(f"Fixture manifest entry is incomplete: {fixture}")

        source_path = fixtures_dir / source_name
        reference_path = fixtures_dir / reference_name
        if not source_path.is_file():
            raise RuntimeError(f"Fixture source is missing: {source_path}")
        if not reference_path.is_file():
            raise RuntimeError(f"Fixture PDF reference is missing: {reference_path}")

        source_stem = source_path.stem.lower()
        transcript_path = _find_latest_matching_file(
            output_root,
            stem_hint=source_stem,
            suffix_predicate=lambda path: path.suffix.lower() == ".txt" and ".summary." not in path.name.lower(),
        )
        summary_suffix = f".summary.{summary_lang}.md" if summary_lang != "auto" else ".summary."
        summary_path = _find_latest_matching_file(
            output_root,
            stem_hint=source_stem,
            suffix_predicate=lambda path: path.name.lower().endswith(summary_suffix) if summary_lang != "auto" else ".summary." in path.name.lower(),
        )

        transcript_text = transcript_path.read_text(encoding="utf-8").strip()
        reference_text = extract_pdf_text(reference_path)
        if not transcript_text:
            raise RuntimeError(f"Transcript output is empty: {transcript_path}")
        if not reference_text:
            raise RuntimeError(f"Reference PDF yielded no text: {reference_path}")

        metrics = similarity_metrics(reference_text, transcript_text)
        if metrics["score"] < similarity_min:
            raise RuntimeError(
                "Transcript similarity below threshold for "
                f"{fixture_id}: score={metrics['score']:.3f}, "
                f"char_ratio={metrics['char_ratio']:.3f}, token_f1={metrics['token_f1']:.3f}, "
                f"required>={similarity_min:.3f}"
            )

        summary_text = summary_path.read_text(encoding="utf-8").strip()
        if len(summary_text) < minimum_summary_chars:
            raise RuntimeError(f"Summary output is too short: {summary_path}")

        missing_headings = [heading for heading in required_headings if heading not in summary_text]
        if missing_headings:
            raise RuntimeError(
                f"Summary output is missing required headings for {fixture_id}: {', '.join(missing_headings)}"
            )

        results.append(
            FixtureValidationResult(
                fixture_id=fixture_id,
                source_file=str(source_path),
                transcript_path=str(transcript_path),
                summary_path=str(summary_path),
                similarity_score=metrics["score"],
                char_ratio=metrics["char_ratio"],
                token_f1=metrics["token_f1"],
                required_similarity=similarity_min,
                summary_headings=list(required_headings),
                summary_char_count=len(summary_text),
            )
        )

    return {
        "fixture_count": len(results),
        "output_root": str(output_root),
        "results": [item.to_dict() for item in results],
    }
