"""Rule tables for where Sudachi's analysis disagrees with JMdict / the parse fixture convention.

Keep these small and documented: each entry is a known, systematic disagreement, not a
per-sentence patch.
"""

from __future__ import annotations

# Words JMdict marks "usually kana" whose Sudachi normalized_form is a rare kanji spelling
# (為る, 居る, 有る, 成る, 此れ, 迚も, ...). For these the lemma stays the kana dictionary form,
# which is also how JMdict keys them first.
KANA_LEMMAS: frozenset[str] = frozenset(
    {
        # する-verbs and other auxiliary-like verbs
        "する", "いる", "ある", "なる", "すぎる", "かかる", "なさる", "おる",
        # adjectives / negation
        "いい", "ない",
        # ko-so-a-do words
        "この", "その", "あの", "どの", "これ", "それ", "あれ", "どれ",
        "ここ", "そこ", "あそこ", "どこ", "こう", "そう", "ああ", "どう",
        "こんな", "そんな", "あんな", "どんな",
        # adverbs and fillers
        "とても", "もう", "まあ", "ええ", "はい",
    }
)  # fmt: skip

# Parts of speech whose lemma is always Sudachi's dictionary form (never the normalised
# kanji form): function words and interjections are keyed in kana in JMdict.
DICTIONARY_FORM_POS: frozenset[str] = frozenset(
    {"助詞", "助動詞", "感動詞", "接頭辞", "接尾辞", "連体詞", "補助記号", "空白", "記号"}
)

# (surface, Sudachi dictionary form) -> lemma. Sudachi folds these into another word's
# paradigm; JMdict (and the fixture) have them as words of their own.
SURFACE_LEMMAS: dict[tuple[str, str], str] = {
    ("に", "だ"): "に",  # 静かに: adverbial に, not a form of だ
    ("じゃ", "だ"): "じゃ",  # じゃない
    ("たら", "た"): "たら",  # conditional
    ("だら", "た"): "たら",
    ("なきゃ", "ない"): "なきゃ",  # colloquial contractions
    ("なくちゃ", "ない"): "なくちゃ",
    ("なけりゃ", "ない"): "なけりゃ",
}

# Prefixes that may fuse with the following word into one JMdict entry (お茶, ご飯, お願い).
FUSING_PREFIXES: frozenset[str] = frozenset({"お", "ご", "御"})

# Volitional (意志推量形) tokens are split into stem + う/よう, as the fixture keeps
# auxiliary chains separate (行こ|う, ましょ|う, 食べ|よう).
VOLITIONAL_CFORM = "意志推量形"
YOU_CONJ_PREFIXES: tuple[str, ...] = ("上一段", "下一段", "カ行変格", "サ行変格")
