
# StatsKnownWordsCurrent

Entries by effective status now (`new` counts only entries that have a `word_status` row).

## Properties

Name | Type
------------ | -------------
`blacklisted` | number
`ignored` | number
`known` | number
`learning` | number
`neverForget` | number
`_new` | number

## Example

```typescript
import type { StatsKnownWordsCurrent } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "blacklisted": null,
  "ignored": null,
  "known": null,
  "learning": null,
  "neverForget": null,
  "_new": null,
} satisfies StatsKnownWordsCurrent

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as StatsKnownWordsCurrent
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


