
# RetentionGroup

Retention of one group.

## Properties

Name | Type
------------ | -------------
`key` | string
`maturePassed` | number
`matureTotal` | number
`passed` | number
`retention` | number
`total` | number
`youngPassed` | number
`youngTotal` | number

## Example

```typescript
import type { RetentionGroup } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "key": null,
  "maturePassed": null,
  "matureTotal": null,
  "passed": null,
  "retention": null,
  "total": null,
  "youngPassed": null,
  "youngTotal": null,
} satisfies RetentionGroup

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as RetentionGroup
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


