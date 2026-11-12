
# GrammarCreatedSchema

Creates a grammar point. Omitted properties take the column default (\'\' / null / [] / false) and get no clock. OPS.md#grammarcreated

## Properties

Name | Type
------------ | -------------
`detectionRules` | [Array&lt;GrammarCreatedSchemaDetectionRulesInner&gt;](GrammarCreatedSchemaDetectionRulesInner.md)
`explanation` | string
`id` | string
`isStub` | boolean
`jlpt` | number
`links` | [Array&lt;GrammarCreatedSchemaLinksInner&gt;](GrammarCreatedSchemaLinksInner.md)
`meaning` | string
`notes` | string
`pattern` | string
`relatedIds` | Set&lt;string&gt;
`title` | string

## Example

```typescript
import type { GrammarCreatedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "detectionRules": null,
  "explanation": null,
  "id": null,
  "isStub": null,
  "jlpt": null,
  "links": null,
  "meaning": null,
  "notes": null,
  "pattern": null,
  "relatedIds": null,
  "title": null,
} satisfies GrammarCreatedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GrammarCreatedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


