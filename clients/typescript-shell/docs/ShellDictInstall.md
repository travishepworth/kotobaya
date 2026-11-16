
# ShellDictInstall

State of the on-device dictionary package and its installer. Also the payload of the `shell.dict` event.

## Properties

Name | Type
------------ | -------------
`installed` | [ShellDictInstallInstalled](ShellDictInstallInstalled.md)
`scope` | string
`state` | string
`doneBytes` | number
`totalBytes` | number
`lastResult` | string
`lastError` | string
`serverReleaseId` | number
`gapFill` | [ShellDictInstallGapFill](ShellDictInstallGapFill.md)

## Example

```typescript
import type { ShellDictInstall } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "installed": null,
  "scope": null,
  "state": null,
  "doneBytes": null,
  "totalBytes": null,
  "lastResult": null,
  "lastError": null,
  "serverReleaseId": null,
  "gapFill": null,
} satisfies ShellDictInstall

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellDictInstall
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


