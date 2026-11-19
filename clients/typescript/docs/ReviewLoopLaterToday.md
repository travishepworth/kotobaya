
# ReviewLoopLaterToday

Learning/relearning cards not ready yet that fall due before `day_ends_at`. Never served early. While `phase` is `reviewing` they are only a number; when nothing is served they make the phase `waiting` (ADR-049). A learning card due at or after `day_ends_at` is not counted.

## Properties

Name | Type
------------ | -------------
`count` | number
`nextDueAt` | Date

## Example

```typescript
import type { ReviewLoopLaterToday } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "count": null,
  "nextDueAt": null,
} satisfies ReviewLoopLaterToday

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReviewLoopLaterToday
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


