
# CoverageDifficulty

Difficulty estimate relative to your other parsed media; null for non-media decks, for single segments and when nothing is parsed.

## Properties

Name | Type
------------ | -------------
`of` | number
`rank` | number
`unknownPer1000` | number

## Example

```typescript
import type { CoverageDifficulty } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "of": null,
  "rank": null,
  "unknownPer1000": null,
} satisfies CoverageDifficulty

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CoverageDifficulty
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


