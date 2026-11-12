
# Meta

Server information and limits.

## Properties

Name | Type
------------ | -------------
`apiVersion` | string
`coreVersion` | string
`dictRelease` | [MetaDictRelease](MetaDictRelease.md)
`highWaterSeq` | number
`limits` | [MetaLimits](MetaLimits.md)
`logEpoch` | string
`serverDeviceId` | string
`serverTime` | Date
`serverVersion` | string

## Example

```typescript
import type { Meta } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "apiVersion": null,
  "coreVersion": null,
  "dictRelease": null,
  "highWaterSeq": null,
  "limits": null,
  "logEpoch": null,
  "serverDeviceId": null,
  "serverTime": null,
  "serverVersion": null,
} satisfies Meta

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Meta
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


