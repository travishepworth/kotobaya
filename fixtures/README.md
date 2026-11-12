# fixtures/

Test data. Each subdirectory has its own README with origin and licence. `private/` is never committed.

| Dir | What |
|---|---|
| `fsrs/` | FSRS golden vectors (py-fsrs 6.3.2) |
| `dict/` | JMdict + KANJIDIC2 subsets (CC BY-SA 4.0) |
| `parse/` | 20 sentences with expected segmentation |
| `media/` | SRT, EPUB, Mokuro samples |
| `jpdb/` | jpdb review export sample (unverified shape) |
| `tools/` | Generators and `test_fixtures.py` (`uv run --with pytest pytest fixtures/tools`) |
