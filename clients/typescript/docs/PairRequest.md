
# PairRequest

Device registration.

## Properties

Name | Type
------------ | -------------
`code` | string
`deviceId` | string
`hlcLast` | string
`kind` | string
`name` | string
`pushTopic` | string

## Example

```typescript
import type { PairRequest } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "code": null,
  "deviceId": null,
  "hlcLast": null,
  "kind": null,
  "name": null,
  "pushTopic": null,
} satisfies PairRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PairRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


