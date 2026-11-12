
# ReviewResult

A review and the card after it.

## Properties

Name | Type
------------ | -------------
`card` | [Card](Card.md)
`review` | [ReviewLog](ReviewLog.md)

## Example

```typescript
import type { ReviewResult } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "card": null,
  "review": null,
} satisfies ReviewResult

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReviewResult
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


