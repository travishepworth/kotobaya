
# GrammarPoint

A grammar point (`app.grammar_point`).

## Properties

Name | Type
------------ | -------------
`createdAt` | Date
`deletedAt` | Date
`detectionRules` | [Array&lt;DetectionRule&gt;](DetectionRule.md)
`exampleCount` | number
`explanation` | string
`id` | string
`isStub` | boolean
`jlpt` | number
`language` | string
`links` | [Array&lt;GrammarLink&gt;](GrammarLink.md)
`meaning` | string
`noteId` | string
`notes` | string
`pattern` | string
`relatedIds` | Array&lt;string&gt;
`title` | string
`updatedAt` | Date

## Example

```typescript
import type { GrammarPoint } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "createdAt": null,
  "deletedAt": null,
  "detectionRules": null,
  "exampleCount": null,
  "explanation": null,
  "id": null,
  "isStub": null,
  "jlpt": null,
  "language": null,
  "links": null,
  "meaning": null,
  "noteId": null,
  "notes": null,
  "pattern": null,
  "relatedIds": null,
  "title": null,
  "updatedAt": null,
} satisfies GrammarPoint

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GrammarPoint
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


