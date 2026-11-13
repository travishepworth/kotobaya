"""Build the JMdict (2000 entries) and KANJIDIC2 subsets from full EDRDG downloads.

Usage: python3 fixtures/tools/make_dict_subset.py [CACHE_DIR]
CACHE_DIR (default ~/.cache/kotobaya-fixtures) must hold JMdict_e.gz and kanjidic2.xml.gz.
Selection: every entry whose keb/reb matches a lemma in the first BASE_SENTENCES (20) records of
parse/sentences.json (the later records only feed JMdict_parse_extra.xml, see below), then
priority-tagged (news1/ichi1/spec1/gai1) entries in file order until 2000.
Extra file: dict/JMdict_parse_extra.xml holds every JMdict entry that a token of parse/sentences.json
needs (its `seq`, or an exact keb/reb match for its lemma) and that is not in the 2000-entry subset.
Kanji subset: every kanji occurring in the selected entries' keb elements.
"""
import gzip
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / ".cache" / "kotobaya-fixtures"
TARGET = 2000
BASE_SENTENCES = 20
NO_LEMMA = {"punct", "name", "symbol", "number"}
PRI = re.compile(r"<(?:ke|re)_pri>(?:news1|ichi1|spec1|gai1)</(?:ke|re)_pri>")


def seq_of(entry: str) -> int:
    return int(re.search(r"<ent_seq>(\d+)</ent_seq>", entry).group(1))


def main() -> None:
    text = gzip.open(CACHE / "JMdict_e.gz", "rt", encoding="utf-8").read()
    head_end = text.index("<JMdict>") + len("<JMdict>")
    header = text[:head_end]
    entries = re.findall(r"<entry>.*?</entry>", text, re.S)
    sentences = json.loads((ROOT / "parse" / "sentences.json").read_text())["sentences"]
    lemmas = {t["lemma"] for s in sentences[:BASE_SENTENCES] for t in s["tokens"]}
    must, rest = [], []
    for e in entries:
        forms = set(re.findall(r"<(?:keb|reb)>(.*?)</(?:keb|reb)>", e))
        if forms & lemmas:
            must.append(e)
        elif PRI.search(e):
            rest.append(e)
    chosen = must[:]
    seen = set(chosen)
    for e in rest:
        if len(chosen) >= TARGET:
            break
        if e not in seen:
            chosen.append(e)
    chosen = chosen[:TARGET]
    chosen_set = set(chosen)
    chosen = [e for e in entries if e in chosen_set]  # restore file order
    (ROOT / "dict" / "JMdict_subset.xml").write_text(
        header + "\n" + "\n".join(chosen) + "\n</JMdict>\n", encoding="utf-8"
    )
    print(f"JMdict: {len(chosen)} entries ({len(must)} forced by sentence lemmas)")

    subset_seqs = {seq_of(e) for e in chosen}
    tokens = [t for s in sentences for t in s["tokens"]]
    need_seqs = {t["seq"] for t in tokens if t.get("seq") is not None}
    need_lemmas = {t["lemma"] for t in tokens if t["pos"] not in NO_LEMMA}
    def forms_of(e: str) -> set[str]:
        return set(re.findall(r"<(?:keb|reb)>(.*?)</(?:keb|reb)>", e))

    extra = [e for e in entries if seq_of(e) not in subset_seqs and seq_of(e) in need_seqs]
    covered = set().union(*(forms_of(e) for e in chosen + extra))
    uncovered = need_lemmas - covered
    extra += [
        e
        for e in entries
        if seq_of(e) not in subset_seqs and seq_of(e) not in need_seqs and forms_of(e) & uncovered
    ]
    (ROOT / "dict" / "JMdict_parse_extra.xml").write_text(
        header + "\n" + "\n".join(extra) + "\n</JMdict>\n", encoding="utf-8"
    )
    print(f"JMdict extra: {len(extra)} entries for parse/sentences.json")

    kanji = {
        c
        for e in chosen
        for keb in re.findall(r"<keb>(.*?)</keb>", e)
        for c in keb
        if "一" <= c <= "鿿"
    }
    kd = gzip.open(CACHE / "kanjidic2.xml.gz", "rt", encoding="utf-8").read()
    kd_head_end = kd.index("</header>") + len("</header>")
    chars = re.findall(r"<character>.*?</character>", kd, re.S)
    keep = [c for c in chars if re.search(r"<literal>(.)</literal>", c).group(1) in kanji]
    (ROOT / "dict" / "kanjidic2_subset.xml").write_text(
        kd[:kd_head_end] + "\n" + "\n".join(keep) + "\n</kanjidic2>\n", encoding="utf-8"
    )
    print(f"KANJIDIC2: {len(keep)} characters ({len(kanji)} distinct kanji in subset)")


if __name__ == "__main__":
    main()
