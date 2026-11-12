
# AnkiConnectResponse

AnkiConnect response envelope; exactly one of the two is non-null (both null never happens; `result` may legitimately be `false`, `0` or an empty array).

## Properties

Name | Type
------------ | -------------
`error` | string
`result` | any

## Example

```typescript
import type { AnkiConnectResponse } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "error": null,
  "result": null,
} satisfies AnkiConnectResponse

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as AnkiConnectResponse
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


