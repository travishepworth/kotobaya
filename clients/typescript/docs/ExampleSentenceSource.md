
# ExampleSentenceSource

C-15. Where an example sentence comes from, for the \"source\" line on the back and a link to the reader.

## Properties

Name | Type
------------ | -------------
`location` | { [key: string]: any; }
`mediaId` | string
`mediaTitle` | string
`segmentId` | string
`segmentTitle` | string
`startMs` | number

## Example

```typescript
import type { ExampleSentenceSource } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "location": null,
  "mediaId": null,
  "mediaTitle": null,
  "segmentId": null,
  "segmentTitle": null,
  "startMs": null,
} satisfies ExampleSentenceSource

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ExampleSentenceSource
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


