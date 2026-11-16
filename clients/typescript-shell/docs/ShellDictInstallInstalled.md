
# ShellDictInstallInstalled

The installed package, or null when none is. Not the whole story of what the device can look up - a package of scope `top60k` lacks entries, and the sidecar cache (`gap_fill`) holds entries fetched one by one; SHELL.md rule L-7 defines the lookup.

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


