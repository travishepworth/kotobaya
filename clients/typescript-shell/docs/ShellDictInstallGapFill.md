
# ShellDictInstallGapFill

Optional (omitted by a shell without gap fill, SHELL-L9). State of the sidecar cache - dictionary entries and kanji that local notes, deck items and queue-relevant cards reference but the installed package lacks (SHELL.md rule L-7, 10.7). Refreshed after every sync; a change is announced by `shell.dict`.

## Properties

Name | Type
------------ | -------------
`cached` | number
`missing` | number

## Example

```typescript
import type { ShellDictInstallGapFill } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "cached": null,
  "missing": null,
} satisfies ShellDictInstallGapFill

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellDictInstallGapFill
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


