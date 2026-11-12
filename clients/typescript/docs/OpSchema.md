
# OpSchema

One sync operation: the unit stored in pending_op (device) and change (server). SYNC.md section 2.

## Properties

Name | Type
------------ | -------------
`causedByOpId` | string
`deviceId` | string
`entityId` | string
`entityType` | string
`hlc` | string
`opId` | string
`opType` | string
`opVersion` | any
`payload` | object
`seq` | number

## Example

```typescript
import type { OpSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "causedByOpId": null,
  "deviceId": null,
  "entityId": null,
  "entityType": null,
  "hlc": null,
  "opId": null,
  "opType": null,
  "opVersion": null,
  "payload": null,
  "seq": null,
} satisfies OpSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as OpSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


