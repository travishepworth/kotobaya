
# EventHello

Payload of `hello`.

## Properties

Name | Type
------------ | -------------
`highWaterSeq` | number
`logEpoch` | string
`serverTime` | Date

## Example

```typescript
import type { EventHello } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "highWaterSeq": null,
  "logEpoch": null,
  "serverTime": null,
} satisfies EventHello

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as EventHello
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


