
# CardBulkResult

Outcome of a bulk call.

## Properties

Name | Type
------------ | -------------
`changed` | number
`dryRun` | boolean
`matched` | number
`opsEmitted` | number
`skipped` | number

## Example

```typescript
import type { CardBulkResult } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "changed": null,
  "dryRun": null,
  "matched": null,
  "opsEmitted": null,
  "skipped": null,
} satisfies CardBulkResult

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CardBulkResult
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


