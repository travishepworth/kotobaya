# dict/ — JMdict and KANJIDIC2 subsets

- Origin: EDRDG, http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz and kanjidic2.xml.gz (downloaded 2026-11-12, cached outside the repo in `~/.cache/kotobaya-fixtures`). Built with `python3 fixtures/tools/make_dict_subset.py`.
- Licence: **CC BY-SA 4.0** (Electronic Dictionary Research and Development Group, https://www.edrdg.org/edrdg/licence.html). These subsets remain under that licence; attribute EDRDG.
- `JMdict_subset.xml`: exactly 2000 entries, original DTD header kept. 293 are forced by lemmas in `parse/sentences.json`; the rest are priority-tagged (news1/ichi1/spec1/gai1) entries in file order.
- `kanjidic2_subset.xml`: every KANJIDIC2 character that appears in a kept entry's `<keb>` (612 characters).
