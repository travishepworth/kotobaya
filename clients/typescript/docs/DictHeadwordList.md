
# DictHeadwordList

Headwords for a batch of entries (`listDictHeadwords`, C-18). Not paginated.

## Properties

Name | Type
------------ | -------------
`items` | [Array&lt;DictHeadword&gt;](DictHeadword.md)

## Example

```typescript
import type { DictHeadwordList } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "items": null,
} satisfies DictHeadwordList

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DictHeadwordList
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


