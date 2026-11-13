
# OverrideToken

One replacement token of a parse override. How the parser turns it into a token is `contracts/api/PARSER.md` 4.4.

## Properties

Name | Type
------------ | -------------
`_class` | string
`lemma` | string
`reading` | string
`seq` | number
`surface` | string

## Example

```typescript
import type { OverrideToken } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "_class": null,
  "lemma": null,
  "reading": null,
  "seq": null,
  "surface": null,
} satisfies OverrideToken

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as OverrideToken
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


