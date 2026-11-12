
# GrammarUpdatedSchema

Sets a subset of a grammar point\'s registers, all at the op\'s hlc. OPS.md#grammarupdated

## Properties

Name | Type
------------ | -------------
`id` | string
`set` | [GrammarUpdatedSchemaSet](GrammarUpdatedSchemaSet.md)

## Example

```typescript
import type { GrammarUpdatedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "set": null,
} satisfies GrammarUpdatedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GrammarUpdatedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


