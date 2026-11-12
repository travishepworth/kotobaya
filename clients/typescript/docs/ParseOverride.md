
# ParseOverride

A tokenisation correction (`app.parse_override`).

## Properties

Name | Type
------------ | -------------
`createdAt` | Date
`deletedAt` | Date
`id` | string
`matchText` | string
`mediaId` | string
`note` | string
`sentenceId` | string
`tokens` | [Array&lt;OverrideToken&gt;](OverrideToken.md)
`updatedAt` | Date

## Example

```typescript
import type { ParseOverride } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "createdAt": null,
  "deletedAt": null,
  "id": null,
  "matchText": null,
  "mediaId": null,
  "note": null,
  "sentenceId": null,
  "tokens": null,
  "updatedAt": null,
} satisfies ParseOverride

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ParseOverride
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


