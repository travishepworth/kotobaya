
# MetaDictRelease

Current dictionary release, or null when none is loaded.

## Properties

Name | Type
------------ | -------------
`id` | number
`version` | string

## Example

```typescript
import type { MetaDictRelease } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "version": null,
} satisfies MetaDictRelease

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MetaDictRelease
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


