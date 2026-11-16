
# ShellDictInstallInstalled

The installed package, or null when none is (then `local_or_proxy` operations proxy, SHELL.md L-7).

## Properties

Name | Type
------------ | -------------
`releaseId` | number
`scope` | string
`bytes` | number

## Example

```typescript
import type { ShellDictInstallInstalled } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "releaseId": null,
  "scope": null,
  "bytes": null,
} satisfies ShellDictInstallInstalled

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellDictInstallInstalled
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


