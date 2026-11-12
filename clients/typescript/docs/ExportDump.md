
# ExportDump

Full data dump.

## Properties

Name | Type
------------ | -------------
`dictReleaseVersion` | string
`exportedAt` | Date
`formatVersion` | number
`highWaterSeq` | number
`serverVersion` | string
`tables` | { [key: string]: Array&lt;{ [key: string]: any; }&gt;; }

## Example

```typescript
import type { ExportDump } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "dictReleaseVersion": null,
  "exportedAt": null,
  "formatVersion": null,
  "highWaterSeq": null,
  "serverVersion": null,
  "tables": null,
} satisfies ExportDump

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ExportDump
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


