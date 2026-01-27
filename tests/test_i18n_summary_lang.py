from transcribemate.core.i18n import (
    normalize_summary_lang,
    summary_lang_label_for_key,
    summary_lang_label_to_key,
    summary_lang_labels,
)


def test_normalize_summary_lang_accepts_known_values():
    assert normalize_summary_lang("auto") == "auto"
    assert normalize_summary_lang("en") == "en"
    assert normalize_summary_lang("CS") == "cs"


def test_normalize_summary_lang_falls_back_to_auto():
    assert normalize_summary_lang("xx") == "auto"
    assert normalize_summary_lang("") == "auto"


def test_summary_lang_labels_include_auto_label():
    labels_en = summary_lang_labels("en")
    labels_cs = summary_lang_labels("cs")
    assert labels_en[0].lower().startswith("auto")
    assert labels_cs[0].lower().startswith("auto")


def test_summary_lang_label_roundtrip_auto():
    auto_en = summary_lang_label_for_key("auto", "en")
    auto_cs = summary_lang_label_for_key("auto", "cs")
    assert summary_lang_label_to_key(auto_en) == "auto"
    assert summary_lang_label_to_key(auto_cs) == "auto"
