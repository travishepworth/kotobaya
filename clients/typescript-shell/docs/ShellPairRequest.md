
# ShellPairRequest

Pairing input. Either `uri`, or `server_url` together with `code`.

## Properties

Name | Type
------------ | -------------
`uri` | string
`serverUrl` | string
`code` | string
`name` | string

## Example

```typescript
import type { ShellPairRequest } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "uri": null,
  "serverUrl": null,
  "code": null,
  "name": null,
} satisfies ShellPairRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellPairRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


