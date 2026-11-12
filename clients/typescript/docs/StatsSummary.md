
# StatsSummary

Today\'s numbers.

## Properties

Name | Type
------------ | -------------
`dayEndsAt` | Date
`doneToday` | [StatsSummaryDoneToday](StatsSummaryDoneToday.md)
`due` | [StatsSummaryDue](StatsSummaryDue.md)
`nextDueAt` | Date
`streakDays` | number
`studyDay` | Date
`totals` | [StatsSummaryTotals](StatsSummaryTotals.md)

## Example

```typescript
import type { StatsSummary } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "dayEndsAt": null,
  "doneToday": null,
  "due": null,
  "nextDueAt": null,
  "streakDays": null,
  "studyDay": null,
  "totals": null,
} satisfies StatsSummary

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as StatsSummary
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


