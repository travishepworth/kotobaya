
# Preset

A scheduling preset (`app.fsrs_params`).

## Properties

Name | Type
------------ | -------------
`createdAt` | Date
`deletedAt` | Date
`desiredRetention` | number
`easyDays` | Array&lt;number&gt;
`fsrsVersion` | string
`fuzz` | boolean
`id` | string
`learningStepsS` | Array&lt;number&gt;
`maximumIntervalDays` | number
`name` | string
`optimizedAt` | Date
`optimizerStats` | { [key: string]: any; }
`relearningStepsS` | Array&lt;number&gt;
`updatedAt` | Date
`weights` | Array&lt;number&gt;
`weightsVersion` | number

## Example

```typescript
import type { Preset } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "createdAt": null,
  "deletedAt": null,
  "desiredRetention": null,
  "easyDays": null,
  "fsrsVersion": null,
  "fuzz": null,
  "id": null,
  "learningStepsS": null,
  "maximumIntervalDays": null,
  "name": null,
  "optimizedAt": null,
  "optimizerStats": null,
  "relearningStepsS": null,
  "updatedAt": null,
  "weights": null,
  "weightsVersion": null,
} satisfies Preset

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Preset
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


