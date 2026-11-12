
# TtsRequest

Text to synthesize. Also the payload of `tts` jobs.

## Properties

Name | Type
------------ | -------------
`text` | string
`voice` | string

## Example

```typescript
import type { TtsRequest } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "text": null,
  "voice": null,
} satisfies TtsRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as TtsRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


