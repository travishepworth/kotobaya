
# JobKind

Job type slug. Payload and result schema per kind are listed on `Job`. Only public kinds; server-internal kinds (C-9, see `Job`) are never values of this enum and never appear in the API.

## Properties

Name | Type
------------ | -------------

## Example

```typescript
import type { JobKind } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
} satisfies JobKind

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobKind
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


