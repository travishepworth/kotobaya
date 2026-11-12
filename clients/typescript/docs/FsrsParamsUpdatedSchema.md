
# FsrsParamsUpdatedSchema

Sets a subset of a preset\'s registers. A winning write to any register except \'name\' forces a replay of every card on the preset. OPS.md#fsrs_paramsupdated

## Properties

Name | Type
------------ | -------------
`id` | string
`set` | [FsrsParamsUpdatedSchemaSet](FsrsParamsUpdatedSchemaSet.md)

## Example

```typescript
import type { FsrsParamsUpdatedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "set": null,
} satisfies FsrsParamsUpdatedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as FsrsParamsUpdatedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


