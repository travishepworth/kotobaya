
# ShellStorage

Bytes used on the device and the audio prefetch preferences.

## Properties

Name | Type
------------ | -------------
`cardAudioBytes` | number
`cardAudioCount` | number
`mediaBytes` | number
`coverBytes` | number
`dictionaryBytes` | number
`totalBytes` | number
`prefetchDays` | number
`budgetMb` | number
`allowMetered` | boolean

## Example

```typescript
import type { ShellStorage } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "cardAudioBytes": null,
  "cardAudioCount": null,
  "mediaBytes": null,
  "coverBytes": null,
  "dictionaryBytes": null,
  "totalBytes": null,
  "prefetchDays": null,
  "budgetMb": null,
  "allowMetered": null,
} satisfies ShellStorage

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellStorage
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


