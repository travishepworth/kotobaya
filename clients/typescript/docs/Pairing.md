
# Pairing

A one-time pairing code.

## Properties

Name | Type
------------ | -------------
`code` | string
`expiresAt` | Date
`pairingUri` | string

## Example

```typescript
import type { Pairing } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "code": null,
  "expiresAt": null,
  "pairingUri": null,
} satisfies Pairing

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Pairing
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


