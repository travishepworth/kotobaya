
# JobPayloadDictRefresh

Options of a dictionary refresh.

## Properties

Name | Type
------------ | -------------
`force` | boolean
`freqLists` | Set&lt;string&gt;

## Example

```typescript
import type { JobPayloadDictRefresh } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "force": null,
  "freqLists": null,
} satisfies JobPayloadDictRefresh

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobPayloadDictRefresh
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


