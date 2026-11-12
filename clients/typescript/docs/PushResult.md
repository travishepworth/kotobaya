
# PushResult

Outcome of one pushed op.

## Properties

Name | Type
------------ | -------------
`error` | [PushResultError](PushResultError.md)
`opId` | string
`seq` | number
`status` | string

## Example

```typescript
import type { PushResult } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "error": null,
  "opId": null,
  "seq": null,
  "status": null,
} satisfies PushResult

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PushResult
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


