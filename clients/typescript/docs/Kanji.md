
# Kanji

A kanji (`ref.kanji`) with the caller\'s overlay.

## Properties

Name | Type
------------ | -------------
`components` | Array&lt;string&gt;
`freqRank` | number
`grade` | number
`jlpt` | number
`kunReadings` | Array&lt;string&gt;
`literal` | string
`meanings` | Array&lt;string&gt;
`nanori` | Array&lt;string&gt;
`noteId` | string
`onReadings` | Array&lt;string&gt;
`radical` | string
`strokeCount` | number

## Example

```typescript
import type { Kanji } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "components": null,
  "freqRank": null,
  "grade": null,
  "jlpt": null,
  "kunReadings": null,
  "literal": null,
  "meanings": null,
  "nanori": null,
  "noteId": null,
  "onReadings": null,
  "radical": null,
  "strokeCount": null,
} satisfies Kanji

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Kanji
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


