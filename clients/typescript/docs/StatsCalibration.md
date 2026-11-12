
# StatsCalibration

Predicted versus actual recall.

## Properties

Name | Type
------------ | -------------
`bins` | [Array&lt;StatsCalibrationBinsInner&gt;](StatsCalibrationBinsInner.md)
`from` | Date
`presetId` | string
`reviewCount` | number
`to` | Date

## Example

```typescript
import type { StatsCalibration } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "bins": null,
  "from": null,
  "presetId": null,
  "reviewCount": null,
  "to": null,
} satisfies StatsCalibration

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as StatsCalibration
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


