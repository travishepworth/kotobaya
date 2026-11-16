
# ShellSyncStatusProgress

While a cycle pulls - how far; null otherwise.

## Properties

Name | Type
------------ | -------------
`done` | number
`total` | number

## Example

```typescript
import type { ShellSyncStatusProgress } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "done": null,
  "total": null,
} satisfies ShellSyncStatusProgress

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellSyncStatusProgress
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


