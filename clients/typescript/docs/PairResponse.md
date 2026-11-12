
# PairResponse

Result of pairing.

## Properties

Name | Type
------------ | -------------
`device` | [Device](Device.md)
`highWaterSeq` | number
`scopes` | [Array&lt;Scope&gt;](Scope.md)
`serverDeviceId` | string
`token` | string
`tokenId` | string

## Example

```typescript
import type { PairResponse } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "device": null,
  "highWaterSeq": null,
  "scopes": null,
  "serverDeviceId": null,
  "token": null,
  "tokenId": null,
} satisfies PairResponse

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PairResponse
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


