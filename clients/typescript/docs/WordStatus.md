
# WordStatus

Word status of an entry (`app.word_status`). `status = coalesce(override_status, auto_status, new)`.

## Properties

Name | Type
------------ | -------------
`autoStatus` | string
`entrySeq` | number
`overrideOrigin` | string
`overrideStatus` | string
`status` | [WordStatusValue](WordStatusValue.md)
`updatedAt` | Date

## Example

```typescript
import type { WordStatus } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "autoStatus": null,
  "entrySeq": null,
  "overrideOrigin": null,
  "overrideStatus": null,
  "status": null,
  "updatedAt": null,
} satisfies WordStatus

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as WordStatus
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


