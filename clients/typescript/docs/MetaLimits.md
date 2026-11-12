
# MetaLimits

Request limits enforced by this server.

## Properties

Name | Type
------------ | -------------
`maxBlobBytes` | number
`maxJsonBytes` | number
`maxOpBytes` | number
`maxPageSize` | number
`maxPullOps` | number
`maxPushBytes` | number
`maxPushOps` | number

## Example

```typescript
import type { MetaLimits } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "maxBlobBytes": null,
  "maxJsonBytes": null,
  "maxOpBytes": null,
  "maxPageSize": null,
  "maxPullOps": null,
  "maxPushBytes": null,
  "maxPushOps": null,
} satisfies MetaLimits

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MetaLimits
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


