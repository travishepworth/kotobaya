
# ClientErrorReport

Body of `POST /client-errors` (C-23): a batch of 1 to 20 client error reports. Limits and client obligations are in the operation. 

## Properties

Name | Type
------------ | -------------
`dropped` | number
`items` | [Array&lt;ClientErrorItem&gt;](ClientErrorItem.md)

## Example

```typescript
import type { ClientErrorReport } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "dropped": null,
  "items": null,
} satisfies ClientErrorReport

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ClientErrorReport
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


