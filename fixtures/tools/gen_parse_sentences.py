"""Extend fixtures/parse/sentences.json to 200 sentences and attach JMdict `seq` to every token.

Usage: python3 fixtures/tools/gen_parse_sentences.py [CACHE_DIR]
CACHE_DIR (default ~/.cache/kotobaya-fixtures) must hold JMdict_e.gz.

The first 20 records of sentences.json are kept verbatim (only `seq` is added). Sentences 21..200
are authored in `parse_sentences_src.py` as pre-split token lists plus a small lexicon; every
sentence was hand-reviewed. The output file is the expected parser output, not a Sudachi dump.

`seq` rule (conservative): candidates are JMdict entries having the lemma as keb or reb; keep those
whose <pos> matches the token's pos class; if several remain prefer those with a priority tag on
the matching form; if exactly one remains it is the seq, otherwise null. Punctuation, names,
numbers and symbols always get null.
"""
import gzip
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from parse_sentences_src import LEXICON, SENTENCES  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / ".cache" / "kotobaya-fixtures"
KEEP = 20
NO_SEQ = {"punct", "name", "symbol", "number"}
PRI = re.compile(r"(?:news1|ichi1|spec1|gai1)")
POSMAP = {
    "noun": ("n", "n-adv", "n-t", "n-suf", "n-pref", "ctr"),
    "pronoun": ("pn",),
    "adjective": ("adj-i", "adj-ix"),
    "adjectival-noun": ("adj-na", "adj-no", "adj-t", "adj-f"),
    "adverb": ("adv", "adv-to"),
    "particle": ("prt",),
    "auxiliary": ("aux", "aux-v", "aux-adj", "cop", "cop-da"),
    "adnominal": ("adj-pn",),
    "numeral": ("num",),
    "suffix": ("suf", "ctr", "n-suf"),
    "conjunction": ("conj",),
    "interjection": ("int", "exp"),
    "prefix": ("pref",),
}
META_COMMENT = (
    "Self-written sentences; segmentation is hand-authored in the style of Sudachi mode C/B "
    "(compounds kept where a JMdict entry exists). `seq` is the JMdict ent_seq where unambiguous, "
    "else null. Original work, CC0."
)


def load_entries() -> list[dict]:
    text = gzip.open(CACHE / "JMdict_e.gz", "rt", encoding="utf-8").read()
    out = []
    for e in re.findall(r"<entry>.*?</entry>", text, re.S):
        seq = int(re.search(r"<ent_seq>(\d+)</ent_seq>", e).group(1))
        forms: dict[str, bool] = {}
        for tag in ("k_ele", "r_ele"):
            for blk in re.findall(rf"<{tag}>.*?</{tag}>", e, re.S):
                f = re.search(r"<(?:keb|reb)>(.*?)</(?:keb|reb)>", blk).group(1)
                forms[f] = forms.get(f, False) or bool(PRI.search(blk))
        pos = set(re.findall(r"<pos>&([^;]+);</pos>", e))
        out.append({"seq": seq, "forms": forms, "pos": pos})
    return out


def pick_seq(index: dict[str, list[dict]], lemma: str, pos: str) -> int | None:
    if pos in NO_SEQ:
        return None
    cands = index.get(lemma, [])
    if pos == "verb":
        cands = [c for c in cands if any(p.startswith("v") for p in c["pos"])]
    elif pos in POSMAP:
        cands = [c for c in cands if c["pos"] & set(POSMAP[pos])]
    if len(cands) > 1:
        cands = [c for c in cands if c["forms"][lemma]]
    return cands[0]["seq"] if len(cands) == 1 else None


def parse_lexicon() -> dict[str, list[tuple[str, str, str]]]:
    lex: dict[str, list[tuple[str, str, str]]] = {}
    for line in LEXICON.strip().splitlines():
        f = line.split()
        if not f or line.startswith("#"):
            continue
        if len(f) == 3:
            surface, lemma, reading, pos = f[0], f[0], f[1], f[2]
        elif len(f) == 4:
            surface, lemma, reading, pos = f
        else:
            raise SystemExit(f"bad lexicon line: {line}")
        lex.setdefault(surface, []).append((lemma, reading, pos))
    return lex


def expand(lex: dict, tok: str) -> dict:
    if tok in ("、", "。", "！", "？"):
        return {"surface": tok, "lemma": tok, "reading": "", "pos": "punct"}
    surface, _, sel = tok.partition("#")
    opts = lex.get(surface)
    if not opts:
        raise SystemExit(f"missing lexicon entry: {surface}")
    if sel:
        opts = [o for o in opts if sel in o]
        if len(opts) != 1:
            raise SystemExit(f"bad selector: {tok}")
    lemma, reading, pos = opts[0]
    return {"surface": surface, "lemma": lemma, "reading": reading, "pos": pos}


def dump(sentences: list[dict]) -> str:
    def tok(t: dict) -> str:
        return "      " + json.dumps(t, ensure_ascii=False, separators=(",", ":"))

    rows = []
    for s in sentences:
        head = json.dumps({k: s[k] for k in ("id", "text", "translation")}, ensure_ascii=False, separators=(",", ":"))
        rows.append("    " + head[:-1] + ',"tokens":[\n' + ",\n".join(tok(t) for t in s["tokens"]) + "]}")
    comment = json.dumps(META_COMMENT, ensure_ascii=False)
    return '{\n  "_comment": ' + comment + ',\n  "sentences": [\n' + ",\n".join(rows) + "\n  ]\n}\n"


def main() -> None:
    path = ROOT / "parse" / "sentences.json"
    base = json.loads(path.read_text())["sentences"][:KEEP]
    lex = parse_lexicon()
    new = []
    for i, (toks, translation) in enumerate(SENTENCES, start=KEEP + 1):
        tokens = [expand(lex, t) for t in toks.split()]
        text = "".join(t["surface"] for t in tokens)
        new.append({"id": f"s{i:02d}", "text": text, "translation": translation, "tokens": tokens})
    assert len(new) == 180, len(new)
    sentences = base + new
    index: dict[str, list[dict]] = {}
    for e in load_entries():
        for f in e["forms"]:
            index.setdefault(f, []).append(e)
    for s in sentences:
        for t in s["tokens"]:
            t["seq"] = pick_seq(index, t["lemma"], t["pos"])
    path.write_text(dump(sentences), encoding="utf-8")
    toks = [t for s in sentences for t in s["tokens"] if t["pos"] not in NO_SEQ]
    nulls = sum(t["seq"] is None for t in toks)
    print(f"{len(sentences)} sentences, {len(toks)} content tokens, {nulls} with null seq")


if __name__ == "__main__":
    main()
