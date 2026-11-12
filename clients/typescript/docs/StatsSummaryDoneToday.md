
# StatsSummaryDoneToday

Work done in the current study day (undone and manual rows excluded).

## Properties

Name | Type
------------ | -------------
`newCards` | number
`retention` | number
`reviews` | number
`timeMs` | number

## Example

```typescript
import type { StatsSummaryDoneToday } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "newCards": null,
  "retention": null,
  "reviews": null,
  "timeMs": null,
} satisfies StatsSummaryDoneToday

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as StatsSummaryDoneToday
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


