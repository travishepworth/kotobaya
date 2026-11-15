# T102 test inputs

Origin: written for this project (self-written text, no third-party content). Licence: CC0.

They only imitate the formats of Kanjium (`accents.tsv`), Yomitan frequency dictionaries
(`freq_a/`, `freq_b/`, zipped by the tests) and Tatoeba sentence pairs (`tatoeba_jpn_eng.tsv`).
The numbers are invented. Headwords match `fixtures/dict/JMdict_subset.xml`.

Exception: `JMdict_freqmatch.xml` (WRK-FREQ-MATCH) holds 42 unmodified JMdict entries (the
そう / だ / か / ね / する / 草 / 猫 / とき / いる / もの / ます / かた homophone sets) copied from EDRDG's `JMdict_e.gz`
(downloaded 2026-11-12, cached in `~/.cache/kotobaya-fixtures`), with the DTD header of
`fixtures/dict/JMdict_subset.xml`. Licence: **CC BY-SA 4.0**, Electronic Dictionary Research
and Development Group (https://www.edrdg.org/edrdg/licence.html).
