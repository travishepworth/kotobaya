# HANDOFF CARD-KIND-BADGE-WEB
Status: DONE (make check MODULE=web green)

- Type field: queue item `card.note.type` (NoteBaseType: vocab|kanji|phrase|grammar|sentence|custom). No contract change.
- Rendering (web only, not shared CSS; assets/card untouched): `web/src/routes/review/+page.svelte`, inside `.card-body`, `<span class="kind-badge">` next to the existing tags. Note: on web the review-type tag is top-left (`.tag`) and the "New" badge (`.new-badge`, `item.is_new`) is top-RIGHT. The kind badge is top-centre: absolute, left 50% + translateX(-50%), max-width 30%, ellipsis, same outlined caption style as New (muted colour).
- i18n: `web/src/routes/review/messages.ts` keys `rv.kind.<type>`: Vocab, Kanji, Phrase, Grammar, Sentence, Custom. Android should use the same English wording.
- No new deps, no tests (UI).
