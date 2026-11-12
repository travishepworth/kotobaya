
# CoverageTokens

Occurrence-weighted counts.

## Properties

Name | Type
------------ | -------------
`known` | number
`knownPct` | number
`learning` | number
`total` | number
`unknown` | number
`unmapped` | number

## Example

```typescript
import type { CoverageTokens } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "known": null,
  "knownPct": null,
  "learning": null,
  "total": null,
  "unknown": null,
  "unmapped": null,
} satisfies CoverageTokens

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CoverageTokens
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


