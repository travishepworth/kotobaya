
# JobPayloadGrammarDetect

Payload of a `grammar-detect` job (C-13). `{}` = fan-out: enqueue one per-media job for every eligible media. `{\"media_id\": …}` = re-detect that one media. Enqueued by the server (fan-out) and the worker (per-media), never through the API. 

## Properties

Name | Type
------------ | -------------
`mediaId` | string

## Example

```typescript
import type { JobPayloadGrammarDetect } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "mediaId": null,
} satisfies JobPayloadGrammarDetect

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobPayloadGrammarDetect
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


