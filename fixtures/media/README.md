# media/ — import samples

- Origin: all text is self-written for this project; no third-party content.
- Licence: CC0.
- `sample.srt`: 7 cues incl. multi-line, an italic sound-effect cue, and an ASS `{\an8}` tag.
- `sample.epub`: EPUB 3, 2 chapters, ruby furigana. Built by `fixtures/tools/make_epub.py`.
- `sample.mokuro`: Mokuro 0.2.0-style JSON (`pages[].blocks[]` with `box`, `vertical`, `lines`, `lines_coords`), 2 pages, 3 blocks. Shape written from memory of mokuro output; unverified against the tool.
- `sample.ass`: ASS with out-of-order Dialogue lines, a Comment line, override tags, `\N`, a drawing line and a sound-effect cue.
- `sample.vtt`: WebVTT with NOTE/STYLE blocks, a cue id, cue settings, `c`/`v`/`ruby` markup and `&nbsp;`.
- `sample.txt` and `sample-sjis.txt`: book text with chapter headings, dialogue lines and a hard-wrapped paragraph; the second is the same text in Shift-JIS (cp932).
- `sample-texthooker.txt`: text-hooker log with timestamp prefixes, consecutive repeats and one non-consecutive repeat.
- `<file>.expected.json` beside each of the above and `sample.srt`: expected extraction, used by `worker/tests/extract/`.
