
# SentenceToken

One parser token of a sentence: token format 1, `contracts/api/PARSER.md` section 1 (C-12), the same value as `app.sentence.tokens` elements and `contracts/api/token.schema.json`. Servers send all ten properties; clients must tolerate unknown ones (later optional additions). Within a sentence tokens are ascending and non-overlapping, offsets are UTF-16 code units into the ORIGINAL sentence text, and `surface` equals that slice; gaps are punctuation and whitespace. 

## Properties

Name | Type
------------ | -------------
`cform` | string
`_class` | string
`e` | number
`lemma` | string
`override` | boolean
`pos` | Array&lt;string&gt;
`reading` | string
`s` | number
`seq` | number
`surface` | string

## Example

```typescript
import type { SentenceToken } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "cform": null,
  "_class": null,
  "e": null,
  "lemma": null,
  "override": null,
  "pos": null,
  "reading": null,
  "s": null,
  "seq": null,
  "surface": null,
} satisfies SentenceToken

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SentenceToken
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


