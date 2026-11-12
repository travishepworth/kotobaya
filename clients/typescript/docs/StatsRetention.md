
# StatsRetention

Retention report.

## Properties

Name | Type
------------ | -------------
`from` | Date
`groupBy` | string
`groups` | [Array&lt;RetentionGroup&gt;](RetentionGroup.md)
`overall` | [RetentionGroup](RetentionGroup.md)
`to` | Date

## Example

```typescript
import type { StatsRetention } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "from": null,
  "groupBy": null,
  "groups": null,
  "overall": null,
  "to": null,
} satisfies StatsRetention

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as StatsRetention
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


