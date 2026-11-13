# grammar-rules

`rules.json`: 61 hand-written detection rules for N3–N1 points of `fixtures/grammar-seed`, each with
at least two positive and two negative example sentences. 【】 in a positive marks the text the grammar
occupies; a detected span must cover it (worker/tests/grammar/test_fixture_rules.py). Rule language:
`worker/src/kotobaya_worker/grammar/DSL.md`.

## Provenance and licence

Every rule and example sentence was written for this project. Nothing was copied from textbooks,
dictionaries, websites or other sources. CC0 1.0.
