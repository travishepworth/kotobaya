
# PresetPatch

Preset options to change. At least one property.

## Properties

Name | Type
------------ | -------------
`desiredRetention` | number
`easyDays` | Array&lt;number&gt;
`fuzz` | boolean
`learningStepsS` | Array&lt;number&gt;
`maximumIntervalDays` | number
`name` | string
`relearningStepsS` | Array&lt;number&gt;

## Example

```typescript
import type { PresetPatch } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "desiredRetention": null,
  "easyDays": null,
  "fuzz": null,
  "learningStepsS": null,
  "maximumIntervalDays": null,
  "name": null,
  "relearningStepsS": null,
} satisfies PresetPatch

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PresetPatch
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


