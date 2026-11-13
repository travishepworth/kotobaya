
# JobResultGrammarDetectFanOut

Result of a fan-out `grammar-detect` job.

## Properties

Name | Type
------------ | -------------
`enqueued` | number
`media` | number
`rulesDigest` | string

## Example

```typescript
import type { JobResultGrammarDetectFanOut } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "enqueued": null,
  "media": null,
  "rulesDigest": null,
} satisfies JobResultGrammarDetectFanOut

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobResultGrammarDetectFanOut
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


