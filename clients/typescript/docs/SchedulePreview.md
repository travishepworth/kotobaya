
# SchedulePreview

Next schedule per rating, computed by the core for an answer given now.

## Properties

Name | Type
------------ | -------------
`again` | [SchedulePreviewOption](SchedulePreviewOption.md)
`easy` | [SchedulePreviewOption](SchedulePreviewOption.md)
`good` | [SchedulePreviewOption](SchedulePreviewOption.md)
`hard` | [SchedulePreviewOption](SchedulePreviewOption.md)

## Example

```typescript
import type { SchedulePreview } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "again": null,
  "easy": null,
  "good": null,
  "hard": null,
} satisfies SchedulePreview

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SchedulePreview
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


