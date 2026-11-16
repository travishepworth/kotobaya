
# ShellDisplay

Display preferences of this device. Device-local, not synced.

## Properties

Name | Type
------------ | -------------
`lockPortrait` | boolean

## Example

```typescript
import type { ShellDisplay } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "lockPortrait": null,
} satisfies ShellDisplay

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellDisplay
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


