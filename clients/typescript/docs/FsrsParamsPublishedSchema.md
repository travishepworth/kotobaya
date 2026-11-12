
# FsrsParamsPublishedSchema

The optimizer publishes new FSRS weights for a preset. Applied iff weights_version > the stored one. OPS.md#fsrs_paramspublished

## Properties

Name | Type
------------ | -------------
`fsrsVersion` | string
`id` | string
`optimizedAt` | number
`optimizerStats` | [FsrsParamsPublishedSchemaOptimizerStats](FsrsParamsPublishedSchemaOptimizerStats.md)
`weights` | Array&lt;any&gt;
`weightsVersion` | number

## Example

```typescript
import type { FsrsParamsPublishedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "fsrsVersion": null,
  "id": null,
  "optimizedAt": null,
  "optimizerStats": null,
  "weights": null,
  "weightsVersion": null,
} satisfies FsrsParamsPublishedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as FsrsParamsPublishedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


