
# QueueItem

One card to show, with everything needed to render and grade it.

## Properties

Name | Type
------------ | -------------
`card` | [Card](Card.md)
`entry` | [DictEntry](DictEntry.md)
`kanji` | [Kanji](Kanji.md)
`preview` | [SchedulePreview](SchedulePreview.md)
`reviewType` | string
`sentence` | [Sentence](Sentence.md)

## Example

```typescript
import type { QueueItem } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "card": null,
  "entry": null,
  "kanji": null,
  "preview": null,
  "reviewType": null,
  "sentence": null,
} satisfies QueueItem

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as QueueItem
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


