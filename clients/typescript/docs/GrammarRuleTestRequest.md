
# GrammarRuleTestRequest

What to test.

## Properties

Name | Type
------------ | -------------
`limit` | number
`mediaId` | string
`rules` | [Array&lt;DetectionRule&gt;](DetectionRule.md)

## Example

```typescript
import type { GrammarRuleTestRequest } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "limit": null,
  "mediaId": null,
  "rules": null,
} satisfies GrammarRuleTestRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GrammarRuleTestRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


