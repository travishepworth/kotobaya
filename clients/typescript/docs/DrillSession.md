
# DrillSession

An ephemeral drill session.

## Properties

Name | Type
------------ | -------------
`id` | string
`items` | [Array&lt;QueueItem&gt;](QueueItem.md)
`mode` | string

## Example

```typescript
import type { DrillSession } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "items": null,
  "mode": null,
} satisfies DrillSession

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DrillSession
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


