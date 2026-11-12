
# ReviewPage

A page of review log rows.

## Properties

Name | Type
------------ | -------------
`items` | [Array&lt;ReviewLog&gt;](ReviewLog.md)
`nextCursor` | string

## Example

```typescript
import type { ReviewPage } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "items": null,
  "nextCursor": null,
} satisfies ReviewPage

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReviewPage
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


