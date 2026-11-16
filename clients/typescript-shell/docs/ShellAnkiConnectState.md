
# ShellAnkiConnectState

Settings and status of the on-device AnkiConnect-compatible server.

## Properties

Name | Type
------------ | -------------
`enabled` | boolean
`port` | number
`apiKey` | string
`origins` | Set&lt;string&gt;
`status` | string
`statusMessage` | string

## Example

```typescript
import type { ShellAnkiConnectState } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "enabled": null,
  "port": null,
  "apiKey": null,
  "origins": null,
  "status": null,
  "statusMessage": null,
} satisfies ShellAnkiConnectState

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellAnkiConnectState
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


