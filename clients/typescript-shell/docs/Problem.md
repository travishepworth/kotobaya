
# Problem

RFC 9457 problem details with Kotobaya extension members.

## Properties

Name | Type
------------ | -------------
`type` | string
`title` | string
`status` | number
`code` | string
`detail` | string
`instance` | string
`requestId` | string
`errors` | [Array&lt;FieldError&gt;](FieldError.md)
`requiredScope` | [Scope](Scope.md)
`existingId` | string

## Example

```typescript
import type { Problem } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "type": null,
  "title": null,
  "status": null,
  "code": null,
  "detail": null,
  "instance": null,
  "requestId": null,
  "errors": null,
  "requiredScope": null,
  "existingId": null,
} satisfies Problem

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Problem
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


