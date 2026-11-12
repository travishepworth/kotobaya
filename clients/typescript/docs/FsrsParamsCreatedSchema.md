
# FsrsParamsCreatedSchema

Creates a scheduling preset. Weights start NULL (core defaults), weights_version 0. OPS.md#fsrs_paramscreated

## Properties

Name | Type
------------ | -------------
`desiredRetention` | number
`easyDays` | Array&lt;number&gt;
`fuzz` | boolean
`id` | string
`learningStepsS` | Array&lt;number&gt;
`maximumIntervalDays` | number
`name` | string
`relearningStepsS` | Array&lt;number&gt;

## Example

```typescript
import type { FsrsParamsCreatedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "desiredRetention": null,
  "easyDays": null,
  "fuzz": null,
  "id": null,
  "learningStepsS": null,
  "maximumIntervalDays": null,
  "name": null,
  "relearningStepsS": null,
} satisfies FsrsParamsCreatedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as FsrsParamsCreatedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


