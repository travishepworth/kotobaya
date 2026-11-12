"""Build the JMdict (2000 entries) and KANJIDIC2 subsets from full EDRDG downloads.

Usage: python3 fixtures/tools/make_dict_subset.py [CACHE_DIR]
CACHE_DIR (default ~/.cache/kotobaya-fixtures) must hold JMdict_e.gz and kanjidic2.xml.gz.
Selection: every entry whose keb/reb matches a lemma in parse/sentences.json, then
priority-tagged (news1/ichi1/spec1/gai1) entries in file order until 2000.
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
PRI = re.compile(r"<(?:ke|re)_pri>(?:news1|ichi1|spec1|gai1)</(?:ke|re)_pri>")


def main() -> None:
    text = gzip.open(CACHE / "JMdict_e.gz", "rt", encoding="utf-8").read()
    head_end = text.index("<JMdict>") + len("<JMdict>")
    header = text[:head_end]
    entries = re.findall(r"<entry>.*?</entry>", text, re.S)
    lemmas = {
        t["lemma"]
        for s in json.loads((ROOT / "parse" / "sentences.json").read_text())["sentences"]
        for t in s["tokens"]
    }
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
