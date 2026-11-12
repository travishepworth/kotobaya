
# Event

One event, as the JSON of an SSE `data:` line and as an ntfy message body.

## Properties

Name | Type
------------ | -------------
`at` | Date
`data` | { [key: string]: any | null; }
`type` | [EventType](EventType.md)

## Example

```typescript
import type { Event } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "at": null,
  "data": null,
  "type": null,
} satisfies Event

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Event
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


