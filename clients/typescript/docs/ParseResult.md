
# ParseResult

Result of `POST /parse`.

## Properties

Name | Type
------------ | -------------
`entries` | [Array&lt;DictEntrySummary&gt;](DictEntrySummary.md)
`parserVersion` | string
`sentences` | [Array&lt;ParsedSentence&gt;](ParsedSentence.md)

## Example

```typescript
import type { ParseResult } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "entries": null,
  "parserVersion": null,
  "sentences": null,
} satisfies ParseResult

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ParseResult
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


