
# ClientErrorItem

One error observed by a web or Android client. Logged as one line, never stored.

## Properties

Name | Type
------------ | -------------
`appVersion` | string
`client` | string
`count` | number
`httpMethod` | string
`httpPath` | string
`httpStatus` | number
`kind` | string
`level` | string
`message` | string
`occurredAt` | Date
`requestId` | string
`route` | string
`stack` | string

## Example

```typescript
import type { ClientErrorItem } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "appVersion": null,
  "client": null,
  "count": null,
  "httpMethod": null,
  "httpPath": null,
  "httpStatus": null,
  "kind": null,
  "level": null,
  "message": null,
  "occurredAt": null,
  "requestId": null,
  "route": null,
  "stack": null,
} satisfies ClientErrorItem

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ClientErrorItem
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


