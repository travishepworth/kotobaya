
# PushRequest

Body of POST /sync/push.

## Properties

Name | Type
------------ | -------------
`ops` | [Array&lt;PushRequestOpsInner&gt;](PushRequestOpsInner.md)

## Example

```typescript
import type { PushRequest } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "ops": null,
} satisfies PushRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PushRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


