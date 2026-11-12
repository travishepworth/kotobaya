
# StatsHeatmap

Reviews per study day.

## Properties

Name | Type
------------ | -------------
`days` | [Array&lt;StatsHeatmapDaysInner&gt;](StatsHeatmapDaysInner.md)
`from` | Date
`to` | Date

## Example

```typescript
import type { StatsHeatmap } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "days": null,
  "from": null,
  "to": null,
} satisfies StatsHeatmap

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as StatsHeatmap
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


