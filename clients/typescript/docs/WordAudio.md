
# WordAudio

ADR-047. The word audio of a vocab note (`QueueItem.word_audio`). Fields added later will be optional, so clients must ignore unknown members.  | `source` | `blob_hash` | `text`, `voice` | Meaning | |---|---|---|---| | `note` | hash, never null | null, null | The first `[sound:<sha256>]` of the note\'s `audio` field. The note\'s own audio always wins. | | `tts` | hash | the pair | Generated speech of the pair, known to this replica. | | `tts` | null | the pair | Not generated yet (or, on the phone, not resolved yet): no speaker button; the client may call `resolveSpeech` with the pair. |  A non-null `blob_hash` says the hash is known, not that the bytes are on this device: offline on the phone an uncached blob answers `404` and the client stays silent. The second and third examples are a deck-materialized note with empty fields (食べる) before and after generation; the first is a note with its own `[sound:]`. 

## Properties

Name | Type
------------ | -------------
`blobHash` | string
`source` | string
`text` | string
`voice` | string

## Example

```typescript
import type { WordAudio } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "blobHash": null,
  "source": null,
  "text": null,
  "voice": null,
} satisfies WordAudio

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as WordAudio
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


