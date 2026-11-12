
# DictEntryReadingsInner


## Properties

Name | Type
------------ | -------------
`appliesTo` | Array&lt;string&gt;
`common` | boolean
`tags` | Array&lt;string&gt;
`text` | string

## Example

```typescript
import type { DictEntryReadingsInner } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "appliesTo": null,
  "common": null,
  "tags": null,
  "text": null,
} satisfies DictEntryReadingsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DictEntryReadingsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


