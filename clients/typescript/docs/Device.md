
# Device

A row of `app.device`.

## Properties

Name | Type
------------ | -------------
`id` | string
`kind` | string
`lastPullSeq` | number
`lastPushAt` | Date
`lastSeenAt` | Date
`name` | string
`pairedAt` | Date
`pushTopic` | string
`revokedAt` | Date

## Example

```typescript
import type { Device } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "kind": null,
  "lastPullSeq": null,
  "lastPushAt": null,
  "lastSeenAt": null,
  "name": null,
  "pairedAt": null,
  "pushTopic": null,
  "revokedAt": null,
} satisfies Device

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Device
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


