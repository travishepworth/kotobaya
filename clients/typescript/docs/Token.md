
# Token

Metadata of a credential (`app.api_token`). The token string and hash are never returned.

## Properties

Name | Type
------------ | -------------
`active` | boolean
`createdAt` | Date
`deviceId` | string
`expiresAt` | Date
`id` | string
`kind` | [TokenKind](TokenKind.md)
`lastUsedAt` | Date
`name` | string
`revokedAt` | Date
`scopes` | [Array&lt;Scope&gt;](Scope.md)
`tokenPrefix` | string

## Example

```typescript
import type { Token } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "active": null,
  "createdAt": null,
  "deviceId": null,
  "expiresAt": null,
  "id": null,
  "kind": null,
  "lastUsedAt": null,
  "name": null,
  "revokedAt": null,
  "scopes": null,
  "tokenPrefix": null,
} satisfies Token

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Token
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


