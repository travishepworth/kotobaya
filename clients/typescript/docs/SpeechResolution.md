
# SpeechResolution

The answer for one `SpeechResolveItem` (`resolveSpeech` has the status table). Fields added later will be optional, so clients must ignore unknown members.

## Properties

Name | Type
------------ | -------------
`blobHash` | string
`durationMs` | number
`retryAfterMs` | number
`source` | string
`status` | string
`text` | string
`voice` | string

## Example

```typescript
import type { SpeechResolution } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "blobHash": null,
  "durationMs": null,
  "retryAfterMs": null,
  "source": null,
  "status": null,
  "text": null,
  "voice": null,
} satisfies SpeechResolution

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SpeechResolution
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


