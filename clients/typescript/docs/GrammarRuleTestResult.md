
# GrammarRuleTestResult

Rule test outcome.

## Properties

Name | Type
------------ | -------------
`matches` | [Array&lt;GrammarRuleTestResultMatchesInner&gt;](GrammarRuleTestResultMatchesInner.md)
`scanned` | number
`truncated` | boolean

## Example

```typescript
import type { GrammarRuleTestResult } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "matches": null,
  "scanned": null,
  "truncated": null,
} satisfies GrammarRuleTestResult

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GrammarRuleTestResult
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


