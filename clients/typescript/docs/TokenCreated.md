
# TokenCreated

A new token including its secret.

## Properties

Name | Type
------------ | -------------
`info` | [Token](Token.md)
`token` | string

## Example

```typescript
import type { TokenCreated } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "info": null,
  "token": null,
} satisfies TokenCreated

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as TokenCreated
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


