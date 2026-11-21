
# ShellEvent

The `event` member of a bridge `event` message: the `Event` envelope of `../openapi.yaml` with the shell\'s type set. `data` by `type`: `hello` - `ShellEventHello`; `sync.changed` - `ShellEventSyncChanged`; `note.created` - `ShellEventNoteCreated`; `shell.connectivity` - `ShellEventConnectivity`; `shell.sync` - `ShellSyncStatus`; `shell.dict` - `ShellDictInstall`; `cache.updated` (C-31) - `ShellEventCacheUpdated`. `job.updated`, `device.paired` and `device.revoked` are never sent in shell mode (SHELL.md 2.6). 

## Properties

Name | Type
------------ | -------------
`type` | string
`at` | Date
`data` | { [key: string]: any | null; }

## Example

```typescript
import type { ShellEvent } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "type": null,
  "at": null,
  "data": null,
} satisfies ShellEvent

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellEvent
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


