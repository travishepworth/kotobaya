
# JobStatus

queued -> running -> succeeded | failed; queued | running -> cancelled.

## Properties

Name | Type
------------ | -------------

## Example

```typescript
import type { JobStatus } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
} satisfies JobStatus

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobStatus
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


