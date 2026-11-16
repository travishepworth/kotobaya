
# ShellInfo

Identity, pairing state and capabilities of the shell.

## Properties

Name | Type
------------ | -------------
`shellVersion` | string
`shellVersionCode` | number
`localApiVersion` | number
`paired` | boolean
`authRequired` | boolean
`serverUrl` | string
`deviceId` | string
`deviceName` | string
`online` | boolean
`capabilities` | Set&lt;string&gt;
`webVersion` | string
`webSource` | string

## Example

```typescript
import type { ShellInfo } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "shellVersion": null,
  "shellVersionCode": null,
  "localApiVersion": null,
  "paired": null,
  "authRequired": null,
  "serverUrl": null,
  "deviceId": null,
  "deviceName": null,
  "online": null,
  "capabilities": null,
  "webVersion": null,
  "webSource": null,
} satisfies ShellInfo

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellInfo
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


