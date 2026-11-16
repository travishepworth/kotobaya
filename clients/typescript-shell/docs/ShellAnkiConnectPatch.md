
# ShellAnkiConnectPatch

AnkiConnect settings to change. At least one property.

## Properties

Name | Type
------------ | -------------
`enabled` | boolean
`port` | number
`apiKey` | string
`origins` | Set&lt;string&gt;

## Example

```typescript
import type { ShellAnkiConnectPatch } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "enabled": null,
  "port": null,
  "apiKey": null,
  "origins": null,
} satisfies ShellAnkiConnectPatch

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellAnkiConnectPatch
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


