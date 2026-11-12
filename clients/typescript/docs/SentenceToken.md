
# SentenceToken

One parser token of a sentence. Shape finalised by the parser contract; unknown properties must be tolerated.

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


