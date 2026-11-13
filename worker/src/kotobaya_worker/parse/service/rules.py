"""Request validation and evaluation for ``POST /internal/grammar/rules/test`` (worker-parse.yaml 1.1.0)."""

from __future__ import annotations

import json
from collections.abc import Callable, Sequence
from typing import Any

from kotobaya_worker.grammar.tester import TestSentence, test_rules

Json = dict[str, Any]
Problem = tuple[int, Json]

MAX_BODY_BYTES = 8 * 1024 * 1024
MAX_RULES = 20
MAX_RULE_CODE_POINTS = 1000
MAX_SENTENCES = 500
MAX_SENTENCE_CODE_POINTS = 20_000
MAX_TOKENS = 20_000
DEFAULT_MAX_MATCHES = 10_000
TOKEN_KEYS = ("s", "e", "surface", "lemma", "reading", "pos", "cform")


def _problem(status: int, code: str, title: str, detail: str) -> Problem:
    return status, {"type": "about:blank", "title": title, "status": status, "code": code,
                    "detail": detail}  # fmt: skip


def _invalid(detail: str) -> Problem:
    return _problem(400, "invalid_request", "Invalid request", detail)


def _too_large(detail: str) -> Problem:
    return _problem(413, "payload_too_large", "Payload too large", detail)


def _bad_text(s: str) -> bool:
    return "\u0000" in s or any(0xD800 <= ord(c) <= 0xDFFF for c in s)


def _is_int(v: Any) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _check_tokens(k: int, text: str, tokens: Any) -> str | None:
    if not isinstance(tokens, list):
        return f"sentences[{k}].tokens must be an array"
    if len(tokens) > MAX_TOKENS:
        return f"sentences[{k}].tokens over {MAX_TOKENS}"
    n16 = len(text.encode("utf-16-le")) // 2
    prev_end = 0
    for t in tokens:
        if not isinstance(t, dict) or any(key not in t for key in TOKEN_KEYS):
            return f"sentences[{k}] has a token missing one of {', '.join(TOKEN_KEYS)}"
        s, e = t["s"], t["e"]
        if not (_is_int(s) and _is_int(e)):
            return f"sentences[{k}] has a token with non-integer s/e"
        if not (0 <= s < e <= n16) or s < prev_end:
            return f"sentences[{k}] tokens must be ascending, non-overlapping, within the text"
        prev_end = e
        if not all(isinstance(t[x], str) for x in ("surface", "lemma", "reading")):
            return f"sentences[{k}] has a token with a non-string surface/lemma/reading"
        pos = t["pos"]
        if not isinstance(pos, list) or not all(isinstance(p, str) for p in pos):
            return f"sentences[{k}] has a token with invalid pos"
        if t["cform"] is not None and not isinstance(t["cform"], str):
            return f"sentences[{k}] has a token with invalid cform"
    return None


def validate(content_type: str | None, body: bytes) -> tuple[list[str], list[Any], int] | Problem:
    """(rules, sentences, max_matches) or an error (status, problem)."""
    if len(body) > MAX_BODY_BYTES:
        return _too_large("body over 8 MiB")
    media = (content_type or "").split(";", 1)[0].strip().lower()
    if media != "application/json":
        return _invalid("Content-Type must be application/json")
    try:
        doc = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return _invalid("body is not valid JSON")
    if not isinstance(doc, dict):
        return _invalid("body must be a JSON object")
    extra = set(doc) - {"rules", "sentences", "max_matches"}
    if extra:
        return _invalid("unknown member: " + ", ".join(sorted(extra)))
    rules = doc.get("rules")
    if not isinstance(rules, list) or not all(isinstance(r, str) for r in rules):
        return _invalid("rules must be an array of strings")
    if not rules:
        return _invalid("rules must not be empty")
    if len(rules) > MAX_RULES:
        return _invalid(f"at most {MAX_RULES} rules")
    for i, r in enumerate(rules):
        if len(r) > MAX_RULE_CODE_POINTS:
            return _invalid(f"rules[{i}] over {MAX_RULE_CODE_POINTS} code points")
        if _bad_text(r):
            return _invalid(f"rules[{i}] contains U+0000 or a lone surrogate")
    sentences = doc.get("sentences", [])
    if not isinstance(sentences, list):
        return _invalid("sentences must be an array")
    if len(sentences) > MAX_SENTENCES:
        return _too_large(f"more than {MAX_SENTENCES} sentences")
    for k, s in enumerate(sentences):
        text = s if isinstance(s, str) else s.get("text") if isinstance(s, dict) else None
        if not isinstance(text, str):
            return _invalid(f"sentences[{k}] must be a string or {{text, tokens}}")
        if isinstance(s, dict) and set(s) - {"text", "tokens"}:
            return _invalid(f"sentences[{k}] has unknown members")
        if text == "":
            return _invalid(f"sentences[{k}] text is empty")
        if len(text) > MAX_SENTENCE_CODE_POINTS:
            return _too_large(f"sentences[{k}] over {MAX_SENTENCE_CODE_POINTS} code points")
        if _bad_text(text):
            return _invalid(f"sentences[{k}] contains U+0000 or a lone surrogate")
        if isinstance(s, dict):
            err = _check_tokens(k, text, s.get("tokens"))
            if err:
                return _invalid(err)
    mm = doc.get("max_matches", DEFAULT_MAX_MATCHES)
    if not _is_int(mm) or not 1 <= mm <= DEFAULT_MAX_MATCHES:
        return _invalid(f"max_matches must be an integer in 1..{DEFAULT_MAX_MATCHES}")
    return rules, sentences, mm


def evaluate(
    rules: Sequence[str],
    sentences: Sequence[Any],
    max_matches: int,
    tokenise: Callable[[Sequence[str]], tuple[str, list[list[Any]]]],
) -> Json:
    """The ``WorkerRuleTestResult``. ``tokenise`` is called only for string sentences."""
    string_at = [k for k, s in enumerate(sentences) if isinstance(s, str)]
    parser_version: str | None = None
    toks: dict[int, list[Any]] = {}
    if string_at:
        parser_version, lists = tokenise([sentences[k] for k in string_at])
        toks = dict(zip(string_at, lists, strict=True))
    items: list[TestSentence] = []
    for k, s in enumerate(sentences):
        if isinstance(s, str):
            items.append(TestSentence(s, toks[k]))
        else:
            items.append(TestSentence(s["text"], s["tokens"]))
    res = test_rules(rules, items).to_json()
    matches = res["matches"]
    return {
        "matches": matches[:max_matches],
        "errors": res["errors"],
        "truncated": len(matches) > max_matches,
        "parser_version": parser_version,
    }
