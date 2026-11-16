
# ShellStoragePatch

Storage preferences to change. At least one property.

## Properties

Name | Type
------------ | -------------
`prefetchDays` | number
`budgetMb` | number
`allowMetered` | boolean
`clearCache` | boolean

## Example

```typescript
import type { ShellStoragePatch } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "prefetchDays": null,
  "budgetMb": null,
  "allowMetered": null,
  "clearCache": null,
} satisfies ShellStoragePatch

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellStoragePatch
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


