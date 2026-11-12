
# DictEntrySensesInner


## Properties

Name | Type
------------ | -------------
`appliesToKana` | Array&lt;string&gt;
`appliesToKanji` | Array&lt;string&gt;
`dialect` | Array&lt;string&gt;
`field` | Array&lt;string&gt;
`gloss` | [Array&lt;DictEntrySensesInnerGlossInner&gt;](DictEntrySensesInnerGlossInner.md)
`info` | Array&lt;string&gt;
`misc` | Array&lt;string&gt;
`pos` | Array&lt;string&gt;
`xref` | Array&lt;string&gt;

## Example

```typescript
import type { DictEntrySensesInner } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "appliesToKana": null,
  "appliesToKanji": null,
  "dialect": null,
  "field": null,
  "gloss": null,
  "info": null,
  "misc": null,
  "pos": null,
  "xref": null,
} satisfies DictEntrySensesInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DictEntrySensesInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


