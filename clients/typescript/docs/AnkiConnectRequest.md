
# AnkiConnectRequest

AnkiConnect request envelope.

## Properties

Name | Type
------------ | -------------
`action` | string
`key` | string
`params` | { [key: string]: any; }
`version` | number

## Example

```typescript
import type { AnkiConnectRequest } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "action": null,
  "key": null,
  "params": null,
  "version": null,
} satisfies AnkiConnectRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as AnkiConnectRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


