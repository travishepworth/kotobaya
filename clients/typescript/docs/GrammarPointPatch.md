
# GrammarPointPatch

Changes to a grammar point. At least one property.

## Properties

Name | Type
------------ | -------------
`detectionRules` | [Array&lt;DetectionRule&gt;](DetectionRule.md)
`explanation` | string
`isStub` | boolean
`jlpt` | number
`links` | [Array&lt;GrammarLink&gt;](GrammarLink.md)
`meaning` | string
`notes` | string
`pattern` | string
`relatedIds` | Set&lt;string&gt;
`title` | string

## Example

```typescript
import type { GrammarPointPatch } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "detectionRules": null,
  "explanation": null,
  "isStub": null,
  "jlpt": null,
  "links": null,
  "meaning": null,
  "notes": null,
  "pattern": null,
  "relatedIds": null,
  "title": null,
} satisfies GrammarPointPatch

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GrammarPointPatch
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


