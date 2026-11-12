# parse/ — sentence segmentation fixtures

- Origin: 20 sentences written for this project; tokenisation hand-authored in the style of Sudachi (compounds kept where JMdict has an entry; verbs/adjectives split into stem + auxiliaries). Treat as the expected output for fixture agreement tests, not as a Sudachi dump.
- Licence: CC0.
- `sentences.json`: `{id, text, translation, tokens[{surface, lemma, reading, pos}]}`; token surfaces concatenate exactly to `text`. Lemmas are covered by `dict/JMdict_subset.xml` (except punctuation).
