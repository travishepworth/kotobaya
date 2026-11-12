
# DevicePatch

Changes to a device. At least one property.

## Properties

Name | Type
------------ | -------------
`name` | string
`pushTopic` | string

## Example

```typescript
import type { DevicePatch } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "name": null,
  "pushTopic": null,
} satisfies DevicePatch

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DevicePatch
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


