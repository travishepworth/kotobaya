
# TokenCreate

New personal access token.

## Properties

Name | Type
------------ | -------------
`expiresAt` | Date
`name` | string
`scopes` | [Set&lt;Scope&gt;](Scope.md)
`withDevice` | boolean

## Example

```typescript
import type { TokenCreate } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "expiresAt": null,
  "name": null,
  "scopes": null,
  "withDevice": null,
} satisfies TokenCreate

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as TokenCreate
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


