#!/usr/bin/env python3
"""Builds the demo dataset (fixtures/demo/*.json). Stdlib only, deterministic: `python3 fixtures/demo/build.py`.

Everything here is invented for UI work (CC0): headwords, glosses, readings and sentences are written by hand,
sequence numbers live in a made-up 9xxxxxx range, ids are fixed UUIDv7-shaped strings. See README.md.
"""

from __future__ import annotations

import json
import random
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

OUT = Path(__file__).resolve().parent
ANCHOR = datetime(2026, 11, 13, 9, 0, tzinfo=timezone.utc)  # "now" of the dataset
STUDY_DAY = "2026-11-13"
DAY_ENDS_AT = "2026-11-14T04:00:00Z"
EPOCH = "2026-01-01T00:00:00Z"

Json = dict[str, Any]


def iso(d: datetime) -> str:
    return d.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def ago(days: float = 0, hours: float = 0) -> str:
    return iso(ANCHOR - timedelta(days=days, hours=hours))


def ahead(days: float = 0, hours: float = 0) -> str:
    return iso(ANCHOR + timedelta(days=days, hours=hours))


def uid(group: int, n: int) -> str:
    """Stable UUIDv7-shaped id: group selects the kind (notes, cards, decks...)."""
    return f"0193a000-{group:04x}-7000-8000-{n:012x}"


def fake_hash(label: str) -> str:
    """64 hex chars derived from a label (stands in for a blob sha256; the blobs do not exist)."""
    h = 0
    for ch in label:
        h = (h * 131 + ord(ch)) % (1 << 64)
    return (f"{h:016x}" * 4)[:64]


# ---------------------------------------------------------------- account

BUILTIN_TYPES: list[Json] = [
    {
        "id": "00000000-0000-7000-8000-000000000101", "name": "Vocab", "base_type": "vocab",
        "fields": [
            {"key": "expression", "label": "Expression", "kind": "text"},
            {"key": "reading", "label": "Reading", "kind": "text"},
            {"key": "meaning", "label": "Meaning", "kind": "text"},
            {"key": "sentence", "label": "Sentence", "kind": "text"},
            {"key": "sentence_translation", "label": "Sentence translation", "kind": "text"},
            {"key": "audio", "label": "Audio", "kind": "audio"},
            {"key": "sentence_audio", "label": "Sentence audio", "kind": "audio"},
            {"key": "image", "label": "Image", "kind": "image"},
            {"key": "notes", "label": "Notes", "kind": "text"},
        ],
        "kinds": {"meaning": True, "reading": True, "listening": False, "production": False, "pitch": False},
    },
    {
        "id": "00000000-0000-7000-8000-000000000102", "name": "Kanji", "base_type": "kanji",
        "fields": [
            {"key": "literal", "label": "Kanji", "kind": "text"},
            {"key": "keyword", "label": "Keyword", "kind": "text"},
            {"key": "readings", "label": "Readings", "kind": "text"},
            {"key": "notes", "label": "Notes", "kind": "text"},
        ],
        "kinds": {"keyword": True, "writing": False, "components": False},
    },
    {
        "id": "00000000-0000-7000-8000-000000000103", "name": "Phrase", "base_type": "phrase",
        "fields": [
            {"key": "expression", "label": "Expression", "kind": "text"},
            {"key": "reading", "label": "Reading", "kind": "text"},
            {"key": "meaning", "label": "Meaning", "kind": "text"},
            {"key": "audio", "label": "Audio", "kind": "audio"},
            {"key": "notes", "label": "Notes", "kind": "text"},
        ],
        "kinds": {"meaning": True, "production": False, "listening": False},
    },
    {
        "id": "00000000-0000-7000-8000-000000000104", "name": "Grammar", "base_type": "grammar",
        "fields": [
            {"key": "pattern", "label": "Pattern", "kind": "text"},
            {"key": "meaning", "label": "Meaning", "kind": "text"},
            {"key": "notes", "label": "Notes", "kind": "text"},
        ],
        "kinds": {"cloze": True, "recognition": False, "spot": False, "production": False},
    },
    {
        "id": "00000000-0000-7000-8000-000000000105", "name": "Sentence", "base_type": "sentence",
        "fields": [
            {"key": "sentence", "label": "Sentence", "kind": "text"},
            {"key": "reading", "label": "Reading", "kind": "text"},
            {"key": "translation", "label": "Translation", "kind": "text"},
            {"key": "audio", "label": "Audio", "kind": "audio"},
            {"key": "image", "label": "Image", "kind": "image"},
            {"key": "notes", "label": "Notes", "kind": "text"},
        ],
        "kinds": {"comprehension": True, "listening": False},
    },
]

VOCAB_NT, KANJI_NT, PHRASE_NT, GRAMMAR_NT, SENTENCE_NT = (t["id"] for t in BUILTIN_TYPES)
CUSTOM_NT = uid(1, 1)
DEFAULT_PRESET = "00000000-0000-7000-8000-000000000010"
SLOW_PRESET = uid(2, 1)


def note_type(t: Json, builtin: bool = True, templates: Json | None = None, css: str = "") -> Json:
    return {
        "id": t["id"], "name": t["name"], "base_type": t["base_type"], "builtin": builtin,
        "fields": t["fields"],
        "templates": templates
        or {k: {"default": on, "front": None, "back": None} for k, on in t["kinds"].items()},
        "css": css, "created_at": EPOCH, "updated_at": EPOCH, "deleted_at": None,
    }


def account() -> Json:
    custom = {
        "id": CUSTOM_NT, "name": "Minimal pair (pitch) — a deliberately long custom note-type name", "base_type": "custom",
        "fields": [
            {"key": "word_a", "label": "Word A", "kind": "text"},
            {"key": "word_b", "label": "Word B", "kind": "text"},
            {"key": "hint", "label": "Hint", "kind": "text"},
            {"key": "audio_a", "label": "Audio A", "kind": "audio"},
        ],
    }
    note_types = [note_type(t) for t in BUILTIN_TYPES] + [
        note_type(
            custom,
            builtin=False,
            templates={
                "pair": {"default": True, "front": "{{word_a}} / {{word_b}}<br>{{hint}}", "back": "{{word_a}}<br>{{audio_a}}"},
                "reverse": {"default": False, "front": "{{word_b}}", "back": "{{word_a}}"},
            },
            css=".kb-card .hint { opacity: .7 }",
        )
    ]
    presets = [
        {
            "id": DEFAULT_PRESET, "name": "Default", "desired_retention": 0.9, "maximum_interval_days": 36500,
            "learning_steps_s": [60, 600], "relearning_steps_s": [600], "fuzz": True, "easy_days": [1, 1, 1, 1, 1, 1, 1],
            "fsrs_version": None, "weights": None, "weights_version": 0, "optimized_at": None, "optimizer_stats": None,
            "created_at": EPOCH, "updated_at": EPOCH, "deleted_at": None,
        },
        {
            "id": SLOW_PRESET, "name": "Gentle (weekends off, 0.85 retention)", "desired_retention": 0.85,
            "maximum_interval_days": 180, "learning_steps_s": [300, 1800, 86400], "relearning_steps_s": [1800],
            "fuzz": False, "easy_days": [1, 1, 1, 1, 1, 0.5, 0], "fsrs_version": None, "weights": None,
            "weights_version": 0, "optimized_at": None, "optimizer_stats": None,
            "created_at": ago(90), "updated_at": ago(3), "deleted_at": None,
        },
    ]
    settings = [
        {"key": "scheduler.timezone", "value": "Asia/Tokyo", "updated_at": ago(200)},
        {"key": "scheduler.grading_buttons", "value": 4, "updated_at": ago(200)},
        {"key": "scheduler.new_per_day", "value": 15, "updated_at": ago(30)},
    ]
    devices = [
        {"id": uid(3, 1), "kind": "server", "name": "kotobaya server", "paired_at": EPOCH, "last_seen_at": ago(0, 0.1),
         "last_pull_seq": 0, "last_push_at": None, "push_topic": None, "revoked_at": None},
        {"id": uid(3, 2), "kind": "android", "name": "Pixel 8 (daily driver)", "paired_at": ago(120), "last_seen_at": ago(0, 2),
         "last_pull_seq": 18234, "last_push_at": ago(0, 2), "push_topic": None, "revoked_at": None},
        {"id": uid(3, 3), "kind": "web", "name": "Firefox on the very long-named laptop in the living room", "paired_at": ago(60),
         "last_seen_at": None, "last_pull_seq": 0, "last_push_at": None, "push_topic": None, "revoked_at": None},
        {"id": uid(3, 4), "kind": "android", "name": "Old tablet", "paired_at": ago(400), "last_seen_at": ago(300),
         "last_pull_seq": 912, "last_push_at": ago(300), "push_topic": None, "revoked_at": ago(250)},
    ]
    tokens = [
        {"id": uid(4, 1), "name": "Yomitan / AnkiConnect", "kind": "personal", "scopes": ["read", "write"],
         "token_prefix": "kby_p_3f9a", "device_id": None, "created_at": ago(80), "last_used_at": ago(1),
         "expires_at": None, "revoked_at": None, "active": True},
        {"id": uid(4, 2), "name": "Backup script", "kind": "personal", "scopes": ["read", "admin"],
         "token_prefix": "kby_p_77c1", "device_id": None, "created_at": ago(300), "last_used_at": None,
         "expires_at": ago(10), "revoked_at": None, "active": False},
    ]
    principal = {
        "kind": "session", "name": "Demo learner", "scopes": ["read", "write", "review", "import", "admin"],
        "token_id": uid(4, 9), "device_id": uid(3, 3), "expires_at": ahead(30), "totp_enabled": False,
    }
    meta = {
        "api_version": "v1", "server_version": "0.1.7-demo", "core_version": "0.1.7", "server_device_id": uid(3, 1),
        "server_time": iso(ANCHOR), "high_water_seq": 18234, "log_epoch": "demo",
        "dict_release": {"id": 3, "version": "demo-2026-11"},
        "limits": {"max_blob_bytes": 104857600, "max_json_bytes": 1048576, "max_op_bytes": 65536,
                   "max_page_size": 500, "max_pull_ops": 1000, "max_push_ops": 1000, "max_push_bytes": 4194304},
    }
    return {"principal": principal, "meta": meta, "settings": settings, "presets": presets,
            "note_types": note_types, "devices": devices, "tokens": tokens}


# ---------------------------------------------------------------- dictionary

@dataclass
class Word:
    seq: int
    headword: str
    reading: str
    senses: list[tuple[list[str], list[str]]]  # (pos, glosses)
    common: bool = True
    freq: int | None = None
    pitch: list[int] = field(default_factory=lambda: [0])
    misc: list[str] = field(default_factory=list)
    info: str = ""


S = 9_000_000
WORDS: list[Word] = [
    Word(S + 1, "猫", "ねこ", [(["n"], ["cat"]), (["n"], ["shamisen (slang)"]), (["n"], ["geisha"])], freq=1840, pitch=[1]),
    Word(S + 2, "日本語", "にほんご", [(["n"], ["Japanese (language)"])], freq=410),
    Word(S + 3, "食べる", "たべる", [(["v1", "vt"], ["to eat"]), (["v1", "vt"], ["to live on (e.g. a salary)", "to make a living"])], freq=230, pitch=[2]),
    Word(S + 4, "行く", "いく", [(["v5k-s", "vi"], ["to go", "to move (towards)", "to head (towards)"]), (["v5k-s", "vi"], ["to proceed", "to take place"])], freq=60),
    Word(S + 5, "学校", "がっこう", [(["n"], ["school"])], freq=520),
    Word(S + 6, "先生", "せんせい", [(["n", "suf"], ["teacher", "instructor", "master"]), (["n"], ["doctor"]), (["n"], ["lawyer, politician or other person of authority (honorific)"])], freq=700, pitch=[3]),
    Word(S + 7, "時間", "じかん", [(["n"], ["time"]), (["n", "ctr"], ["hours"]), (["n"], ["period", "class", "lesson"])], freq=150),
    Word(S + 8, "大丈夫", "だいじょうぶ", [(["adj-na"], ["safe", "secure", "sound", "OK", "all right"]), (["adv"], ["certainly", "surely", "undoubtedly"]), (["int"], ["no thanks", "I'm good"])], freq=900, pitch=[3]),
    Word(S + 9, "勉強", "べんきょう", [(["n", "vs"], ["study"]), (["n", "vs"], ["diligence", "working hard"]), (["n", "vs"], ["experience", "lesson (for the future)"]), (["n", "vs"], ["discount", "price reduction"])], freq=640),
    Word(S + 10, "取る", "とる", [
        (["v5r", "vt"], ["to take", "to pick up", "to grab", "to catch"]),
        (["v5r", "vt"], ["to pass", "to hand", "to give"]),
        (["v5r", "vt"], ["to get", "to obtain", "to acquire", "to win", "to receive", "to earn", "to take (e.g. a vacation)"]),
        (["v5r", "vt"], ["to adopt (a method, proposal, etc.)", "to take (a measure, attitude, etc.)", "to choose"]),
        (["v5r", "vt"], ["to remove", "to get rid of", "to take off"]),
        (["v5r", "vt"], ["to take away", "to steal", "to rob"]),
        (["v5r", "vt"], ["to eat", "to have (a meal)", "to take (vitamins, etc.)"]),
        (["v5r", "vt"], ["to pick (e.g. flowers)", "to gather", "to extract (e.g. juice)", "to catch (e.g. fish)"]),
        (["v5r", "vt"], ["to take up (time, space)", "to occupy", "to spare", "to set aside"]),
        (["v5r", "vt"], ["to secure", "to reserve", "to save", "to put aside", "to keep"]),
        (["v5r", "vt"], ["to take (a joke, criticism, etc.)", "to interpret", "to understand", "to make out", "to grasp"]),
        (["v5r", "vt"], ["to record", "to take down"]),
        (["v5r", "vt"], ["to subscribe to (e.g. a newspaper)", "to take", "to buy", "to get"]),
        (["v5r", "vt"], ["to order", "to have delivered"]),
        (["v5r", "vt"], ["to charge", "to fine", "to take (tax)"]),
        (["v5r", "vt"], ["to take (e.g. a wife)", "to take on (e.g. an apprentice)", "to adopt", "to accept"]),
        (["v5r", "vt"], ["to compete (in sumo, cards, etc.)", "to play"]),
    ], freq=95, pitch=[1], info="Usually written using kana alone in some senses; the long list is the point of this entry."),
    Word(S + 11, "掛ける", "かける", [
        (["v1", "vt"], ["to hang up (e.g. a coat, a picture on the wall)", "to let hang", "to suspend (from)", "to hoist (e.g. a sail)", "to raise (e.g. a flag)"]),
        (["v1", "vt"], ["to put on (e.g. a blanket)", "to put on top of", "to cover"]),
        (["v1", "vt"], ["to put on (glasses, etc.)", "to wear (a necklace, etc.)"]),
        (["v1", "vt"], ["to make (a call)"]),
        (["v1", "vt"], ["to spend (time, money)", "to expend", "to use"]),
        (["v1", "vt"], ["to pour (water, etc.) onto", "to splash", "to spray", "to dash", "to sprinkle"]),
        (["v1", "vt"], ["to multiply (arithmetic operation)"]),
        (["v1", "vt"], ["to secure (e.g. lock)", "to fasten", "to tie"]),
        (["v1", "vt"], ["to sit down on", "to sit on", "to perch on"]),
    ], freq=480, pitch=[2]),
    Word(S + 12, "鬱", "うつ", [(["n"], ["depression", "low spirits", "melancholy"]), (["adj-na"], ["dense (growth of trees)", "thick", "luxuriant"])], common=False, freq=None, misc=["rare kanji"]),
    Word(S + 13, "薔薇", "ばら", [(["n"], ["rose"])], common=False, freq=9800, misc=["usually written using kana alone"], pitch=[0]),
    Word(S + 14, "齟齬", "そご", [(["n", "vs"], ["inconsistency", "discrepancy", "contradiction", "conflict", "friction"])], common=False, freq=24000, pitch=[1]),
    Word(S + 15, "躊躇", "ちゅうちょ", [(["n", "vs"], ["hesitation", "indecision", "vacillation", "wavering"])], common=False, freq=15000, pitch=[1]),
    Word(S + 16, "魑魅魍魎", "ちみもうりょう", [(["n", "yoji"], ["evil spirits of rivers and mountains", "all kinds of monsters (demons, goblins)"]), (["n", "yoji"], ["people who scheme for their own private gain (fig.)"])], common=False, pitch=[1]),
    Word(S + 17, "顰蹙", "ひんしゅく", [(["n", "vs"], ["frowning (with disapproval)", "displeasure", "antipathy", "contempt"])], common=False, freq=30000),
    Word(S + 18, "檸檬", "れもん", [(["n"], ["lemon (Citrus limon)"])], common=False, misc=["ateji", "usually written using kana alone"]),
    Word(S + 19, "蟷螂", "かまきり", [(["n"], ["praying mantis"])], common=False, misc=["usually written using kana alone"]),
    Word(S + 20, "独立行政法人", "どくりつぎょうせいほうじん", [(["n"], ["incorporated administrative agency (semi-autonomous body that performs government functions)"])], common=False, freq=12000),
    Word(S + 21, "国際連合教育科学文化機関", "こくさいれんごうきょういくかがくぶんかきかん", [(["n", "org"], ["United Nations Educational, Scientific and Cultural Organization", "UNESCO"])], common=False),
    Word(S + 22, "東京特許許可局", "とうきょうとっきょきょかきょく", [(["n"], ["Tokyo Patent Approval Office (fictitious; a famous tongue twister)"])], common=False),
    Word(S + 23, "ありがとう", "ありがとう", [(["int"], ["thank you", "thanks"])], freq=300, pitch=[2]),
    Word(S + 24, "よろしく", "よろしく", [(["adv", "exp"], ["well", "properly", "suitably"]), (["exp"], ["best regards", "please remember me", "please treat me favourably"])], freq=800, pitch=[0]),
    Word(S + 25, "見る", "みる", [(["v1", "vt"], ["to see", "to look", "to watch", "to view", "to observe"]), (["v1", "vt"], ["to examine", "to look over", "to assess", "to check", "to judge"]), (["v1", "vt"], ["to look after", "to keep an eye on", "to take care of"])], freq=70, pitch=[1]),
    Word(S + 26, "電車", "でんしゃ", [(["n"], ["train", "electric train"])], freq=1200),
    Word(S + 27, "雨", "あめ", [(["n"], ["rain"]), (["n"], ["rainy day", "rainy weather"])], freq=950, pitch=[1]),
    Word(S + 28, "飴", "あめ", [(["n"], ["(hard) candy", "toffee"])], freq=6000, pitch=[0]),
    Word(S + 29, "橋", "はし", [(["n"], ["bridge"])], freq=2100, pitch=[2]),
    Word(S + 30, "箸", "はし", [(["n"], ["chopsticks"])], freq=4800, pitch=[1]),
    Word(S + 31, "未", "み", [(["pref"], ["not yet", "un-"])], freq=3500),
    Word(S + 32, "末", "まつ", [(["n-suf"], ["the end of", "close of (the month)"])], freq=1900),
    Word(S + 33, "侘び寂び", "わびさび", [(["n"], ["wabi-sabi", "aesthetic sense in Japanese art emphasising quiet simplicity and subdued refinement"])], common=False),
    Word(S + 34, "木漏れ日", "こもれび", [(["n"], ["sunlight filtering through trees"])], common=False, freq=20000),
    Word(S + 35, "寿限無寿限無五劫の擦り切れ", "じゅげむじゅげむごこうのすりきれ", [(["n"], ["(opening of a famously long rakugo name)"])], common=False),
    Word(S + 36, "鰯", "いわし", [(["n"], ["sardine (esp. the Japanese sardine, Sardinops melanostictus)", "pilchard"])], common=False, pitch=[0]),
    Word(S + 37, "ずつ", "ずつ", [(["prt"], ["apiece", "each", "at a time"])], freq=1300),
    Word(S + 38, "空", "そら", [(["n"], ["sky", "the heavens"]), (["n"], ["weather"]), (["n"], ["(far-off) place", "location"]), (["n"], ["state of mind", "feeling"])], freq=1100, pitch=[1]),
    Word(S + 39, "気", "き", [(["n"], ["spirit", "mind", "heart"]), (["n"], ["nature", "disposition"]), (["n"], ["motivation", "intention"]), (["n"], ["mood", "feelings"])], freq=200),
    Word(S + 40, "本", "ほん", [(["n"], ["book", "volume", "script"]), (["pref"], ["this", "present"]), (["ctr"], ["counter for long cylindrical things"])], freq=120, pitch=[1]),
]
WORD = {w.headword: w for w in WORDS}

KANJI_CHARS = set("猫日本語食行学校先生時間大丈夫勉強取掛鬱薔薇齟齬躊躇魑魅魍魎顰蹙檸檬蟷螂独立行政法人国際連合教育科文化機関東京特許可局見電車雨飴橋箸未末侘寂木漏鰯空気")


def is_kanji(ch: str) -> bool:
    return "一" <= ch <= "鿿"


def entry(w: Word) -> Json:
    kanji_form = w.headword if any(is_kanji(c) for c in w.headword) else None
    return {
        "seq": w.seq, "language": "ja", "headword": w.headword, "headword_reading": w.reading,
        "is_common": w.common, "freq_rank": w.freq, "retired": False, "note_id": None, "status": "new",
        "freqs": [{"list_id": "demo-freq", "rank": w.freq}] if w.freq else [],
        "kanji_forms": [{"text": kanji_form, "common": w.common, "tags": []}] if kanji_form else [],
        "readings": [{"text": w.reading, "common": w.common, "tags": [], "applies_to": []}],
        "kanji": sorted({c for c in w.headword if is_kanji(c)}, key=w.headword.index),
        "pitch": [{"reading": w.reading, "positions": w.pitch}],
        "pos": sorted({p for pos, _ in w.senses for p in pos}),
        "misc": w.misc,
        "senses": [
            {"pos": pos, "gloss": [{"lang": "eng", "text": g} for g in gl], "misc": [], "field": [], "dialect": [],
             "info": [w.info] if (w.info and i == 0) else [], "xref": [], "applies_to_kanji": [], "applies_to_kana": []}
            for i, (pos, gl) in enumerate(w.senses)
        ],
    }


KANJI: list[Json] = []


def kanji(lit: str, meanings: list[str], on: list[str], kun: list[str], strokes: int | None, grade: int | None,
          jlpt: int | None, freq: int | None, components: str = "") -> None:
    KANJI.append({"literal": lit, "meanings": meanings, "on_readings": on, "kun_readings": kun, "nanori": [],
                  "stroke_count": strokes, "grade": grade, "jlpt": jlpt, "freq_rank": freq, "radical": None,
                  "components": list(components), "note_id": None})


kanji("日", ["day", "sun", "Japan", "counter for days"], ["ニチ", "ジツ"], ["ひ", "-び", "-か"], 4, 1, 5, 1)
kanji("本", ["book", "present", "main", "origin", "true", "real", "counter for long cylindrical things"], ["ホン"], ["もと"], 5, 1, 5, 10)
kanji("猫", ["cat"], ["ビョウ"], ["ねこ"], 11, None, 2, 1702, "犭苗")
kanji("語", ["word", "speech", "language"], ["ゴ"], ["かた.る", "かた.らう"], 14, 2, 5, 301, "言吾")
kanji("食", ["eat", "food"], ["ショク", "ジキ"], ["く.う", "く.らう", "た.べる", "は.む"], 9, 2, 5, 328)
kanji("取", ["take", "fetch", "take up"], ["シュ"], ["と.る", "と.り", "-ど.り"], 8, 3, 4, 227, "耳又")
kanji("鬱", ["gloom", "depression", "melancholy"], ["ウツ"], ["ふさ.ぐ", "しげ.る"], 29, None, None, None, "缶木冖鬯彡")
kanji("薔", ["rose", "red"], ["ショウ", "ソウ", "ショク"], ["みずたで", "ばら"], 16, None, None, None)
kanji("薇", ["bracken", "fernbrake"], ["ビ"], ["ぜんまい"], 16, None, None, None)
kanji("齟", ["disagree", "gnash teeth"], ["ソ", "ショ"], ["くいちが.う"], 20, None, None, None, "歯且")
kanji("魑", ["mountain demon"], ["チ"], [], 21, None, None, None)
kanji("鰯", ["sardine"], [], ["いわし"], 21, None, None, None, "魚弱")
kanji("雨", ["rain"], ["ウ"], ["あめ", "あま-", "-さめ"], 8, 1, 5, 950)
kanji("学", ["study", "learning", "science"], ["ガク"], ["まな.ぶ"], 8, 1, 5, 63)
kanji("気", ["spirit", "mind", "air", "atmosphere", "mood"], ["キ", "ケ"], ["いき"], 6, 1, 5, 113)

TATOEBA = {
    S + 1: [(9101, "猫が窓のそばで寝ている。", "The cat is sleeping by the window.")],
    S + 3: [(9102, "朝ご飯を食べましたか。", "Did you eat breakfast?"), (9103, "辛いものは食べられない。", "I can't eat spicy food.")],
    S + 10: [(9104, "塩を取ってください。", "Please pass the salt."), (9105, "休みを取って、海に行った。", "I took some time off and went to the sea.")],
    S + 27: [(9106, "明日は雨が降るでしょう。", "It will probably rain tomorrow.")],
}


# ---------------------------------------------------------------- media and sentences

@dataclass
class Tok:
    surface: str
    reading: str = ""
    lemma: str = ""
    cls: str = "content"
    seq: int | None = None


def T(surface: str, reading: str = "", cls: str = "content", lemma: str = "", seq: int | None = None) -> Tok:
    w = WORD.get(lemma or surface)
    return Tok(surface, reading or (w.reading if w and surface == w.headword else surface), lemma or surface, cls,
               seq if seq is not None else (w.seq if w else None))


P = lambda s: Tok(s, s, s, "particle")  # noqa: E731
A = lambda s: Tok(s, s, s, "auxiliary")  # noqa: E731
O = lambda s: Tok(s, s, s, "other")  # noqa: E731

MEDIA_SPECS = [
    ("anime", "file", "Neko no Jikan (demo anime)", "parsed",
     [("episode", "第1話 はじまりの日"), ("episode", "第2話 雨の日の橋"), ("episode", "第3話 — an episode title long enough to wrap twice on a phone")]),
    ("novel", "file", "木漏れ日の図書館（上）", "parsed", [("chapter", "序章"), ("chapter", "第一章 檸檬"), ("chapter", "第二章 魑魅魍魎")]),
    ("manga", "file", "Bara to Kamakiri vol. 1", "parsed", [("volume", "Volume 1")]),
    ("web", "url", "A blog post about 独立行政法人 paperwork", "parsed", [("whole", "")]),
    ("vn", "paste", "Untitled paste", "failed", []),
    ("anime", "jpdb", "jpdb: Slice of life deck (imported)", "parsed", [("episode", "Episode 1")]),
]

SENTENCES_A: list[list[Tok]] = [
    [T("猫"), P("が"), T("日本語"), P("を"), T("勉強"), A("して"), A("いる"), O("。")],
    [T("先生"), P("、"), T("大丈夫"), A("です"), P("か"), O("？")],
    [T("雨"), P("の"), T("日"), P("に"), T("橋"), P("を"), T("渡る"), O("。")],
    [T("箸"), P("で"), T("飴"), P("を"), T("取る"), P("の"), P("は"), T("難しい"), O("。")],
    [T("ありがとう"), O("、"), T("よろしく"), O("！")],
    [T("学校"), P("まで"), T("電車"), P("で"), T("行く"), O("。")],
    [T("空"), P("を"), T("見る"), O("。")],
    [T("時間"), P("が"), T("ない"), O("。")],
    [T("鰯"), P("を"), T("食べる"), O("。")],
    [T("本"), P("を"), T("一冊"), P("ずつ"), T("読む"), O("。")],
    [T("気"), P("に"), T("する"), A("な"), O("。")],
    [T("躊躇"), A("せず"), P("に"), T("言う"), O("。")],
]
SENTENCES_B: list[list[Tok]] = [
    [T("木漏れ日"), P("の"), T("中"), P("で"), T("檸檬"), P("を"), T("見る"), O("。")],
    [T("魑魅魍魎"), P("が"), T("跋扈"), A("する"), T("世界"), O("。")],
    [T("齟齬"), P("が"), T("生じる"), O("。")],
    [T("顰蹙"), P("を"), T("買う"), O("。")],
    [T("侘び寂び"), P("を"), T("感じる"), O("。")],
    [T("鬱"), P("な"), T("気分"), O("。")],
    [T("薔薇"), P("と"), T("蟷螂"), O("。")],
]


def sentence(media_id: str, segment_id: str, n: int, toks: list[Tok], audio: bool, start_ms: int | None,
             page: int | None) -> Json:
    text, out, pos = "", [], 0
    for t in toks:
        out.append({"s": pos, "e": pos + len(t.surface), "surface": t.surface, "lemma": t.lemma, "reading": t.reading,
                    "pos": ["名詞"] if t.cls == "content" else ["助詞"], "cform": None, "class": t.cls, "seq": t.seq,
                    "override": False})
        text += t.surface
        pos += len(t.surface)
    unknown = sum(1 for t in toks if t.cls == "content" and t.seq is not None and t.seq % 3 == 0)
    return {
        "id": uid(20, n), "media_id": media_id, "segment_id": segment_id, "ordinal": n % 1000, "text": text,
        "tokens": out, "start_ms": start_ms, "end_ms": start_ms + 2400 if start_ms is not None else None,
        "audio_blob_hash": fake_hash(f"s{n}") if audio else None, "image_blob_hash": None,
        "location": {"page": page} if page is not None else None, "origin": "media", "unknown_count": unknown,
        "grammar_spans": [],
    }


def media() -> Json:
    items, sentences, coverage = [], [], {}
    n = 0
    for i, (kind, source, title, status, segs) in enumerate(MEDIA_SPECS, start=1):
        mid = uid(10, i)
        segments = [
            {"id": uid(11, i * 10 + j), "media_id": mid, "kind": sk, "ordinal": j, "title": st,
             "sentence_count": 0, "token_count": 0, "unmapped_token_count": 0}
            for j, (sk, st) in enumerate(segs)
        ]
        pool = SENTENCES_A if i in (1, 6) else SENTENCES_B if i == 2 else SENTENCES_A[:4] if i in (3, 4) else []
        for j, seg in enumerate(segments):
            for k, toks in enumerate(pool):
                if (k + j) % (len(segments) or 1) != 0 and len(segments) > 1 and k > 1:
                    continue
                n += 1
                s = sentence(mid, seg["id"], n, toks, audio=(kind == "anime" and k % 2 == 0),
                             start_ms=(k * 4100 + j * 600000) if kind == "anime" else None,
                             page=(k + 1) if kind in ("novel", "manga") else None)
                sentences.append(s)
                seg["sentence_count"] += 1
                seg["token_count"] += len(s["tokens"])
                seg["unmapped_token_count"] += sum(1 for t in s["tokens"] if t["seq"] is None and t["class"] == "content")
        items.append({
            "id": mid, "kind": kind, "source": source, "title": title, "language": "ja",
            "parse_status": status, "parse_error": "Could not detect the text encoding (demo failure)" if status == "failed" else None,
            "content_blob_hash": fake_hash(title) if status == "parsed" else None, "content_version": 1 if status == "parsed" else 0,
            "cover_blob_hash": None, "jpdb_id": "anime/123456" if source == "jpdb" else None,
            "created_at": ago(100 - i * 9), "updated_at": ago(20 - i), "deleted_at": None, "segments": segments,
        })
        if status == "parsed":
            total_u = 40 + i * 37
            known_u = int(total_u * (0.55 + 0.06 * i))
            learning_u = int(total_u * 0.08)
            total_t = total_u * 6
            known_t = int(total_t * (0.78 + 0.03 * i))
            learning_t = int(total_t * 0.05)
            coverage[mid] = {
                "deck_id": uid(30, 100 + i), "segment_id": None, "computed_at": ago(0, 3),
                "hide": ["ignored"], "ignored_count": i * 2,
                "unique": {"total": total_u, "known": known_u, "learning": learning_u, "unknown": total_u - known_u - learning_u},
                "tokens": {"total": total_t, "known": known_t, "learning": learning_t, "unknown": total_t - known_t - learning_t,
                           "unmapped": 3 * i, "known_pct": round(100 * known_t / total_t, 1)},
                "curve": [{"words": w, "known_pct": round(min(99.5, 60 + 40 * (1 - 0.82 ** (w / 10))), 1)}
                          for w in (0, 10, 25, 50, 100, 200, 400)],
                "difficulty": {"rank": i, "of": 5, "unknown_per_1000": round(180 / i, 1)} if i < 6 else None,
                "segments": [
                    {"segment_id": s["id"], "ordinal": s["ordinal"], "title": s["title"], "unique_total": 20 + 7 * s["ordinal"],
                     "unique_unknown": 6 + s["ordinal"], "token_total": 120 + 40 * s["ordinal"],
                     "known_pct": round(82 - 3.5 * s["ordinal"], 1)}
                    for s in segments
                ],
            }
    overrides = [
        {"id": uid(21, 1), "match_text": "日本語", "media_id": None, "sentence_id": None,
         "tokens": [{"surface": "日本", "seq": None}, {"surface": "語", "seq": None}], "note": "",
         "created_at": ago(10), "updated_at": ago(10), "deleted_at": None},
        {"id": uid(21, 2), "match_text": "一冊ずつ", "media_id": uid(10, 1), "sentence_id": None,
         "tokens": [{"surface": "一冊", "seq": None}, {"surface": "ずつ", "seq": S + 37}], "note": "",
         "created_at": ago(4), "updated_at": ago(4), "deleted_at": None},
    ]
    jpdb_vocab = [
        {"jpdb_vid": 1000000 + k, "spelling": sp, "reading": rd, "entry_seq": None, "resolution": "unresolved",
         "resolved_at": None, "first_seen_at": ago(30), "occurrence_total": occ,
         "media": [{"media_id": uid(10, 6), "title": MEDIA_SPECS[5][2], "occurrence_count": occ}]}
        for k, (sp, rd, occ) in enumerate([("取る", "とる", 31), ("あめ", "あめ", 12), ("", "はし", 7),
                                           ("一入", "ひとしお", 2), ("魑魅魍魎", "ちみもうりょう", 1)], start=1)
    ]
    grammar = [
        {"id": uid(22, k), "title": t, "pattern": p, "meaning": m, "explanation": "", "notes": "", "jlpt": j,
         "language": "ja", "is_stub": False, "example_count": c, "detection_rules": [], "links": [], "related_ids": [],
         "note_id": None, "created_at": ago(50), "updated_at": ago(5), "deleted_at": None}
        for k, (t, p, m, j, c) in enumerate([("〜ている", "Vている", "ongoing action or resulting state", 5, 14),
                                              ("〜ずつ", "Nずつ", "each; at a time", 4, 3),
                                              ("〜ずに", "Vずに", "without doing", 3, 2),
                                              ("〜に違いない", "Vに違いない", "must be; no doubt", 2, 0)], start=1)
    ]
    return {"media": items, "sentences": sentences, "coverage": coverage, "parse_overrides": overrides,
            "jpdb_vocab": jpdb_vocab, "grammar_points": grammar}


# ---------------------------------------------------------------- notes, cards, decks

def learning(media_data: Json) -> Json:
    rnd = random.Random(7)
    notes: list[Json] = []
    nid = 0
    cid = 0

    def card(note_id: str, kind: str, i: int, *, state: str | None = None, suspended: bool = False, leech: bool = False,
             preset: str = DEFAULT_PRESET) -> Json:
        nonlocal cid
        cid += 1
        st = state or rnd.choice(["new", "learning", "review", "review", "review", "relearning"])
        reviewed = st != "new"
        interval = None if st in ("new", "learning") else rnd.choice([1, 3, 8, 21, 55, 130, 400])
        lapses = (9 + i % 4) if leech else (rnd.randint(0, 3) if reviewed else 0)
        return {
            "id": uid(13, cid), "note_id": note_id, "kind": kind, "preset_id": preset, "state": st,
            "step": 0 if st in ("learning", "relearning") else None,
            "stability": round(rnd.uniform(0.5, 300), 2) if reviewed else None,
            "difficulty": round(rnd.uniform(2, 9), 2) if reviewed else None,
            "due_at": ago(0, rnd.randint(1, 30)) if reviewed and cid % 2 == 0 else (ahead(rnd.randint(1, 60)) if reviewed else None),
            "interval_days": interval, "last_review_at": ago(rnd.randint(1, 40)) if reviewed else None,
            "reps": rnd.randint(1, 40) if reviewed else 0, "lapses": lapses, "flags": 1 if cid % 17 == 0 else 0,
            "suspended": suspended, "buried_until": None, "leech": leech, "merged_into_id": None,
            "created_at": ago(200 - (cid % 150)), "updated_at": ago(cid % 30), "deleted_at": None,
        }

    def note(nt: str, ntype: str, fields: dict[str, str], kinds: list[str], *, seq: int | None = None,
             lit: str | None = None, gp: str | None = None, tags: list[str] | None = None, mnemonic: str = "",
             source_sentence: str = "", leech: bool = False, suspended: bool = False, states: list[str] | None = None,
             preset: str = DEFAULT_PRESET) -> Json:
        nonlocal nid
        nid += 1
        note_id = uid(12, nid)
        n = {
            "id": note_id, "note_type_id": nt, "type": ntype, "language": "ja", "fields": fields, "tags": tags or [],
            "mnemonic": mnemonic, "entry_seq": seq, "kanji_literal": lit, "grammar_point_id": gp,
            "source_sentence": source_sentence, "source_sentence_id": None, "source_media_id": None,
            "origin": "manual", "merged_into_id": None, "created_by_device_id": uid(3, 3),
            "created_at": ago(220 - nid), "updated_at": ago(nid % 25), "deleted_at": None, "cards": [],
        }
        n["cards"] = [card(note_id, k, nid, state=(states[i] if states and i < len(states) else None),
                           leech=leech and i == 0, suspended=suspended, preset=preset) for i, k in enumerate(kinds)]
        notes.append(n)
        return n

    empty_vocab = {"expression": "", "reading": "", "meaning": "", "sentence": "", "sentence_translation": "",
                   "audio": "", "sentence_audio": "", "image": "", "notes": ""}
    # Dictionary-backed vocab: fields empty, everything comes from the entry.
    for k, w in enumerate(WORDS):
        if w.seq in (S + 31, S + 32, S + 37):
            continue
        f = dict(empty_vocab)
        if k % 4 == 1:
            f["sentence"] = "猫が日本語を勉強している。" if k % 8 == 1 else ""
            f["audio"] = fake_hash(w.headword)  # audio / no audio
        if k % 6 == 2:
            f["meaning"] = "My own gloss overrides the dictionary — and it is quite long, to see how it wraps."
        note(VOCAB_NT, "vocab", f, ["meaning", "reading"], seq=w.seq,
             tags=(["jlpt-n5"] if w.common else ["rare", "kanji-heavy"]) + (["anime"] if k % 5 == 0 else []),
             mnemonic="ね (neko) sounds like 'neck-o': the cat sleeps around your neck." if w.headword == "猫" else "",
             leech=w.headword in ("齟齬", "躊躇", "掛ける"), suspended=w.headword == "東京特許許可局",
             preset=SLOW_PRESET if k % 9 == 4 else DEFAULT_PRESET)
    # Manual vocab without a dictionary reference.
    note(VOCAB_NT, "vocab", {**empty_vocab, "expression": "推し", "reading": "おし", "meaning": "one's favourite (idol, character)"},
         ["meaning", "reading"], tags=["slang"])
    note(VOCAB_NT, "vocab", {**empty_vocab, "expression": "", "reading": "", "meaning": ""}, ["meaning"], tags=["broken"],
         states=["new"])  # empty everything: the renderer's empty state
    # Kanji notes.
    for k in KANJI:
        note(KANJI_NT, "kanji", {"literal": "", "keyword": "", "readings": "", "notes": ""}, ["keyword"], lit=k["literal"],
             tags=["kanji"])
    # Phrases, grammar, sentences, custom.
    note(PHRASE_NT, "phrase", {"expression": "お疲れ様です", "reading": "おつかれさまです", "meaning": "thanks for your hard work (greeting among colleagues)", "audio": fake_hash("otsukare"), "notes": ""}, ["meaning", "listening"])
    note(PHRASE_NT, "phrase", {"expression": "猫の手も借りたい", "reading": "ねこのてもかりたい", "meaning": "so busy one would even take help from a cat", "audio": "", "notes": ""}, ["meaning"], tags=["idiom"])
    for gp in media_data["grammar_points"][:3]:
        note(GRAMMAR_NT, "grammar", {"pattern": gp["pattern"], "meaning": gp["meaning"], "notes": ""}, ["cloze", "recognition"], gp=gp["id"])
    for s in media_data["sentences"][:4]:
        note(SENTENCE_NT, "sentence", {"sentence": s["text"], "reading": "", "translation": "(demo translation)", "audio": s["audio_blob_hash"] or "", "image": "", "notes": ""}, ["comprehension"], source_sentence=s["text"])
    note(CUSTOM_NT, "custom", {"word_a": "雨（あ↘め）", "word_b": "飴（あめ↗）", "hint": "rain vs. candy", "audio_a": ""}, ["pair", "reverse"], tags=["pitch"])
    note(CUSTOM_NT, "custom", {"word_a": "橋（はし↗）", "word_b": "箸（は↘し）", "hint": "bridge vs. chopsticks", "audio_a": fake_hash("hashi")}, ["pair"], tags=["pitch"])

    # Mark dictionary entries that have a note.
    by_seq = {n["entry_seq"]: n["id"] for n in notes if n["entry_seq"] is not None}
    by_lit = {n["kanji_literal"]: n["id"] for n in notes if n["kanji_literal"] is not None}

    # Decks: 8 user, 6 smart, 12 list, one media deck per parsed media (5) + one more user = 32.
    decks: list[Json] = []

    def deck(n: int, dtype: str, name: str, *, priority: int = 5, enabled: bool = True, query: str | None = None,
             list_key: str | None = None, media_id: str | None = None, items: int = 0, new_pd: int | None = None,
             rev_pd: int | None = None) -> Json:
        d = {"id": uid(30, n), "type": dtype, "name": name, "language": "ja", "priority": priority, "enabled": enabled,
             "query": query, "list_key": list_key, "media_id": media_id, "item_count": items,
             "new_cards_per_day": new_pd, "reviews_per_day": rev_pd, "settings": {},
             "content_blob_hash": None, "content_version": 0, "created_at": ago(150 - n), "updated_at": ago(n % 20),
             "deleted_at": None}
        decks.append(d)
        return d

    user_names = ["Core vocabulary", "Anime words", "Kanji (radicals first)", "Mined from novels",
                  "A user deck with a remarkably long name that will not fit on one line anywhere", "Pitch accent pairs",
                  "Inbox", "Disabled experiments"]
    for k, nm in enumerate(user_names, start=1):
        deck(k, "user", nm, priority=9 - k if k < 9 else 0, enabled=k != 8, items=[42, 17, 15, 9, 3, 2, 0, 5][k - 1],
             new_pd=[20, 5, None, None, None, 10, None, None][k - 1])
    smart = [("Leeches", "is:leech"), ("Due this week", "due:<7d"), ("Rare kanji", "tag:rare"),
             ("Listening cards", "kind:listening"), ("Suspended", "is:suspended"), ("Broken query (demo)", "deck:\"Core")]
    for k, (nm, q) in enumerate(smart, start=20):
        deck(k, "smart", nm, query=q, priority=3)
    lists = [("JLPT N5", "jlpt-n5", 662), ("JLPT N4", "jlpt-n4", 632), ("JLPT N3", "jlpt-n3", 1797), ("JLPT N2", "jlpt-n2", 1831),
             ("JLPT N1", "jlpt-n1", 3427), ("Kanken 10", "kanken-10", 80), ("Kanken 1 (all 6,000 kanji)", "kanken-1", 6355),
             ("Core 2k", "core-2k", 2000), ("Core 6k", "core-6k", 6000), ("Top 1000 by frequency", "freq-1k", 1000),
             ("Onomatopoeia", "onomatopoeia", 412), ("Yojijukugo", "yoji", 0)]
    for k, (nm, key, cnt) in enumerate(lists, start=40):
        deck(k, "list", nm, list_key=key, items=cnt, priority=1, enabled=k < 44)
    for i, m in enumerate(media_data["media"], start=1):
        if m["parse_status"] == "parsed":
            deck(100 + i, "media", m["title"], media_id=m["id"], items=len({t["seq"] for s in media_data["sentences"] if s["media_id"] == m["id"] for t in s["tokens"] if t["seq"]}), priority=4)

    # Deck items for user, list and media decks.
    entries_by_seq = {w.seq: w for w in WORDS}

    def summary(w: Word) -> Json:
        return {"seq": w.seq, "headword": w.headword, "headword_reading": w.reading,
                "glosses": [g for _, gl in w.senses for g in gl][:6], "pos": sorted({p for pos, _ in w.senses for p in pos}),
                "is_common": w.common, "freq_rank": w.freq, "retired": False, "note_id": by_seq.get(w.seq), "status": "new"}

    deck_items: dict[str, list[Json]] = {}
    for d in decks:
        if d["type"] == "smart":
            continue
        if d["type"] == "media":
            seqs = sorted({t["seq"] for s in media_data["sentences"] if s["media_id"] == d["media_id"] for t in s["tokens"] if t["seq"]})
        else:
            offset = sum(map(ord, d["id"])) % 5
            seqs = [w.seq for w in WORDS][offset:][: min(12, d["item_count"] or 0)]
            if d["name"] == "Core vocabulary":
                seqs = [w.seq for w in WORDS]
        rows = []
        for p, sq in enumerate(seqs):
            w = entries_by_seq.get(sq)
            if not w:
                continue
            rows.append({"deck_id": d["id"], "item_key": f"e:{sq}", "entry_seq": sq, "note_id": None, "position": p,
                         "occurrence_count": 1 + (sq * 7) % 23, "added_at": ago(30 - p % 30),
                         "status": ["new", "learning", "known", "new"][sq % 4], "entry": summary(w)})
        if d["name"] == "Core vocabulary":
            rows.append({"deck_id": d["id"], "item_key": f"n:{uid(12, 41)}", "entry_seq": None, "note_id": uid(12, 41),
                         "position": len(rows), "occurrence_count": 1, "added_at": ago(2), "status": None, "entry": None})
        deck_items[d["id"]] = rows

    # Struggle: hot words / kanji, confusables.
    def note_summary(n: Json) -> Json:
        return {k: n[k] for k in ("id", "note_type_id", "type", "fields", "tags", "mnemonic", "entry_seq", "kanji_literal",
                                  "grammar_point_id", "source_sentence")}

    vocab_notes = [n for n in notes if n["type"] == "vocab" and n["entry_seq"]]
    hot_words = []
    for k, n in enumerate(vocab_notes[:12]):
        w = entries_by_seq[n["entry_seq"]]
        hot_words.append({"note_id": n["id"], "note": note_summary(n), "entry_seq": w.seq, "entry": summary(w),
                          "cards": n["cards"], "score": round(9.5 - k * 0.7, 2), "failures_14d": max(0, 9 - k)})
    hot_kanji = [{"literal": k["literal"], "meanings": k["meanings"], "note_id": by_lit.get(k["literal"]),
                  "score": round(7.2 - i * 0.6, 2), "word_count": 3 + i} for i, k in enumerate(KANJI[:8])]
    by_head = {entries_by_seq[n["entry_seq"]].headword: n for n in vocab_notes}
    confusables = []
    for a, b, reason, status in [("雨", "飴", "homophone", "suggested"), ("橋", "箸", "homophone", "confirmed"),
                                 ("未", "末", "look_alike", "suggested"), ("取る", "掛ける", "meaning", "rejected"),
                                 ("見る", "行く", "meaning", "suggested")]:
        na, nb = by_head.get(a), by_head.get(b)
        if not na or not nb:
            continue
        confusables.append({"note_a_id": na["id"], "note_b_id": nb["id"], "note_a": note_summary(na), "note_b": note_summary(nb),
                            "reason": reason, "status": status, "created_at": ago(12), "updated_at": ago(2)})
    return {"notes": notes, "decks": decks, "deck_items": deck_items, "hot_words": hot_words, "hot_kanji": hot_kanji,
            "confusables": confusables, "deck_lists": [
                {"list_key": key, "title": nm, "item_count": cnt, "deck_id": next((d["id"] for d in decks if d["list_key"] == key), None)}
                for nm, key, cnt in lists] + [{"list_key": "wanikani-1", "title": "WaniKani level 1", "item_count": 120, "deck_id": None}]}


# ---------------------------------------------------------------- stats

def stats() -> Json:
    rnd = random.Random(11)
    start = ANCHOR - timedelta(days=364)
    days = []
    for d in range(365):
        day = start + timedelta(days=d)
        active = rnd.random() < (0.55 + 0.4 * d / 365)
        reviews = int(rnd.gauss(80 + d / 3, 35)) if active else 0
        reviews = max(0, reviews)
        days.append({"date": day.strftime("%Y-%m-%d"), "reviews": reviews, "new_cards": min(reviews, rnd.randint(0, 20)),
                     "again": reviews // 9, "time_ms": reviews * 7200})
    groups = [("meaning", 3120, 2800), ("reading", 2410, 2050), ("listening", 300, 230), ("keyword", 880, 790),
              ("cloze", 40, 31), ("comprehension", 0, 0)]

    def rg(key: str, total: int, passed: int) -> Json:
        return {"key": key, "total": total, "passed": passed, "retention": round(passed / total, 4) if total else None,
                "young_total": total // 3, "young_passed": int(passed / 3 * 0.92), "mature_total": total - total // 3,
                "mature_passed": passed - int(passed / 3 * 0.92)}

    tot = sum(g[1] for g in groups)
    pas = sum(g[2] for g in groups)
    forecast_days = [{"date": (ANCHOR + timedelta(days=d)).strftime("%Y-%m-%d"), "due": 53 if d == 0 else max(0, int(rnd.gauss(70, 25)))}
                     for d in range(30)]
    hours = [{"starts_at": iso(ANCHOR.replace(minute=0) + timedelta(hours=h)), "due": rnd.randint(0, 12)} for h in range(24)]
    series = [{"date": (ANCHOR - timedelta(weeks=25 - w)).strftime("%Y-%m-%d"), "known": 300 + w * w * 3 + w * 20} for w in range(26)]
    bins = [{"lower": b / 10, "upper": (b + 1) / 10, "predicted": round(b / 10 + 0.05, 3),
             "actual": round(min(1, max(0, b / 10 + 0.05 + rnd.uniform(-0.06, 0.04))), 3), "count": int(30 + b * b * 25)}
            for b in range(3, 10)]
    return {
        "summary": {"study_day": STUDY_DAY, "day_ends_at": DAY_ENDS_AT,
                    "due": {"new": 12, "learning": 4, "review": 37},
                    "done_today": {"reviews": 41, "new_cards": 5, "time_ms": 1_260_000, "retention": 0.878},
                    "streak_days": 143, "next_due_at": ahead(0, 0.5),
                    "totals": {"notes": 0, "cards": 0, "suspended": 0, "leeches": 0}},
        "heatmap": {"from": start.strftime("%Y-%m-%d"), "to": ANCHOR.strftime("%Y-%m-%d"), "days": days},
        "retention": {"from": (ANCHOR - timedelta(days=30)).strftime("%Y-%m-%d"), "to": ANCHOR.strftime("%Y-%m-%d"),
                      "group_by": "card_kind", "overall": rg("overall", tot, pas), "groups": [rg(*g) for g in groups]},
        "forecast": {"days": forecast_days, "hours": hours},
        "known_words": {"current": {"known": series[-1]["known"], "never_forget": 37, "learning": 212, "new": 4180,
                                    "ignored": 64, "blacklisted": 3}, "series": series},
        "calibration": {"from": (ANCHOR - timedelta(days=90)).strftime("%Y-%m-%d"), "to": ANCHOR.strftime("%Y-%m-%d"),
                        "preset_id": None, "review_count": sum(b["count"] for b in bins), "bins": bins},
    }


# ---------------------------------------------------------------- scenarios

SCENARIOS = {
    "default": {
        "description": "A lived-in account: ~70 notes, 32 decks, 6 media, a year of reviews, 53 cards due.",
        "queue_counts": {"new": 12, "learning": 4, "review": 37},
        "empty": [],
    },
    "due900": {
        "description": "The same account after a long break: 900 cards due today.",
        "queue_counts": {"new": 20, "learning": 15, "review": 865},
        "empty": [],
    },
    "empty": {
        "description": "A brand-new account: built-in note types, the Default preset and settings only; no notes, cards, decks, media or history.",
        "queue_counts": {"new": 0, "learning": 0, "review": 0},
        "empty": ["notes", "decks", "deck_items", "hot_words", "hot_kanji", "confusables", "media", "sentences", "coverage",
                  "parse_overrides", "jpdb_vocab", "grammar_points", "stats", "tokens"],
    },
}


def main() -> None:
    m = media()
    learn = learning(m)
    st = stats()
    cards = [c for n in learn["notes"] for c in n["cards"]]
    st["summary"]["totals"] = {"notes": len(learn["notes"]), "cards": len(cards),
                               "suspended": sum(c["suspended"] for c in cards), "leeches": sum(c["leech"] for c in cards)}
    dictionary = {"entries": [entry(w) for w in WORDS], "kanji": KANJI,
                  "tatoeba": {str(k): [{"id": i, "text": t, "translation": tr} for i, t, tr in v] for k, v in TATOEBA.items()}}
    note_by_seq = {n["entry_seq"]: n["id"] for n in learn["notes"] if n["entry_seq"]}
    for e in dictionary["entries"]:
        e["note_id"] = note_by_seq.get(e["seq"])
        e["status"] = ["new", "learning", "known", "new", "never_forget"][e["seq"] % 5] if e["note_id"] else "new"
    files = {"account.json": account(), "dictionary.json": dictionary, "learning.json": learn, "media.json": m,
             "stats.json": st, "scenarios.json": SCENARIOS}
    for name, data in files.items():
        (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"notes={len(learn['notes'])} cards={len(cards)} decks={len(learn['decks'])} media={len(m['media'])} "
          f"sentences={len(m['sentences'])} entries={len(WORDS)} kanji={len(KANJI)}")


if __name__ == "__main__":
    main()
