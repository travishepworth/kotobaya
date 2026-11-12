
# Principal

The credential making the request.

## Properties

Name | Type
------------ | -------------
`deviceId` | string
`expiresAt` | Date
`kind` | [TokenKind](TokenKind.md)
`name` | string
`scopes` | [Array&lt;Scope&gt;](Scope.md)
`tokenId` | string
`totpEnabled` | boolean

## Example

```typescript
import type { Principal } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "deviceId": null,
  "expiresAt": null,
  "kind": null,
  "name": null,
  "scopes": null,
  "tokenId": null,
  "totpEnabled": null,
} satisfies Principal

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Principal
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


