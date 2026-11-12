# AnkiDroid provider call fixtures

Each `*.json` file here is a recorded sequence of `ContentResolver` calls into
`content://com.ichi2.anki.flashcards/...` and what the provider must answer. `AnkiProviderReplayTest`
replays every file, in order, against a fresh `InMemoryAnkiStore(firstId = 1000)` (deck "Default"
id 1, note type "Basic" id 1 with Front/Back; new ids count up from 1000).

**Provenance:** the current files are SEEDED, not recorded: they follow the S01 spike README's URI
table and AnkiDroid's `AddContentApi` call sequence (getDeckList, addNewDeck, getModelList,
getFieldList, findDuplicateNotes, addNote, addNotes) as remembered. When the human sends the call log
from a real Takoboto session (logcat tag `KotobayaAnkiApi`), add a file per session in this format.

**Known deliberate differences, to confirm against a real call log:**
- Adding a deck whose name already exists returns the existing deck's id. AnkiDroid (as remembered)
  throws IllegalArgumentException instead.
- A `notes` selection we can't fully parse returns no rows (AnkiDroid runs it as SQL).
- With no persistent store registered (cold start beyond a 5 s wait), every call fails with
  IllegalStateException; nothing is ever saved to memory only.

## Format

```json
{
  "description": "...",
  "source": "seeded | recorded <date> <client and version>",
  "calls": [ { ...call... } ]
}
```

A call has `method` (`query` | `insert` | `bulkInsert` | `update` | `delete` | `call` | `getType`), `uri`,
and as needed `projection`, `selection`, `args`, `sort`, `values` (object), `valuesList` (array of
objects, bulkInsert), `callMethod`/`arg` (call). JSON integers become `Long` content values.

`expect` holds one of: `columns` + `rows` (query; cells compared as `Cursor.getString`), `rowCount`,
`uri` (insert), `count` (bulkInsert/update/delete), `type` (getType), `bundle: null` (call), or
`throws` (simple exception class name). `"logsUnknown": true` asserts the call added a `!!` line to
the provider call log.

`"save": "name"` on an insert stores the returned URI's last path segment; any later string may use
`${name}`.
