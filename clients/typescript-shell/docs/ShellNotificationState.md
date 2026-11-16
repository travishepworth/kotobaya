
# ShellNotificationState

Notification permission and reminder preferences (device-local).

## Properties

Name | Type
------------ | -------------
`permission` | string
`canRequest` | boolean
`nudgesEnabled` | boolean
`nudgeThreshold` | number
`eveningHour` | number

## Example

```typescript
import type { ShellNotificationState } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "permission": null,
  "canRequest": null,
  "nudgesEnabled": null,
  "nudgeThreshold": null,
  "eveningHour": null,
} satisfies ShellNotificationState

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellNotificationState
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


