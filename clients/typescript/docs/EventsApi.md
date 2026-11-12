# EventsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**streamEvents**](EventsApi.md#streamevents) | **GET** /events | Server-sent events - job progress, sync activity, new notes, device changes. |



## streamEvents

> string streamEvents(types)

Server-sent events - job progress, sync activity, new notes, device changes.

A &#x60;text/event-stream&#x60; (HTML Living Standard) that stays open. Each event is  &#x60;&#x60;&#x60; id: &lt;n&gt; event: &lt;type&gt; data: &lt;one line of JSON: the Event object&gt;  &#x60;&#x60;&#x60;  - &#x60;id&#x60; counts up from 1 per connection. There is **no replay**: &#x60;Last-Event-ID&#x60; is   ignored. After (re)connecting, a client refetches what it shows; the first event on   every connection is &#x60;hello&#x60; and carries &#x60;high_water_seq&#x60; for that purpose. - &#x60;data&#x60; is always a single-line JSON object with the schema &#x60;Event&#x60;   (&#x60;{\&quot;type\&quot;, \&quot;at\&quot;, \&quot;data\&quot;}&#x60;); &#x60;event:&#x60; repeats &#x60;type&#x60;. - A comment line &#x60;: ping&#x60; is sent every 25 seconds so proxies keep the connection. - Delivery is best effort; events may be coalesced. Slow consumers are disconnected   when more than 1000 events are buffered for them. - The stream ends with HTTP close when the credential is revoked or expires.  | &#x60;type&#x60; | &#x60;data&#x60; schema | Sent when | |---|---|---| | &#x60;hello&#x60; | &#x60;EventHello&#x60; | Once, first, on every connection. | | &#x60;sync.changed&#x60; | &#x60;EventSyncChanged&#x60; | One or more ops were logged (any origin). Coalesced to at most one per second; carries the new &#x60;high_water_seq&#x60;. Clients with a replica pull; others refetch. | | &#x60;note.created&#x60; | &#x60;EventNoteCreated&#x60; | A &#x60;note.created&#x60; op was applied (once per note). | | &#x60;job.updated&#x60; | &#x60;Job&#x60; | A job was created, changed status, or its progress changed (at most one per job per second while running; the terminal state is always sent). | | &#x60;device.paired&#x60; | &#x60;EventDevice&#x60; | &#x60;POST /devices/pair&#x60; succeeded. | | &#x60;device.revoked&#x60; | &#x60;EventDevice&#x60; | A device was revoked. |  &#x60;types&#x60; filters the stream (comma-separated); &#x60;hello&#x60; is always sent.  **ntfy forwarding.** The server posts the same &#x60;Event&#x60; JSON as the message body to the &#x60;push_topic&#x60; of every non-revoked device for: &#x60;job.updated&#x60; with a terminal status, and &#x60;sync.changed&#x60; (coalesced to at most one per minute per device, and not to the device that originated the change). 

### Example

```ts
import {
  Configuration,
  EventsApi,
} from '@kotobaya/api-client';
import type { StreamEventsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new EventsApi(config);

  const body = {
    // Set<EventType> | Only these event types (plus `hello`). Default - all. (optional)
    types: ["job.updated","sync.changed"],
  } satisfies StreamEventsRequest;

  try {
    const data = await api.streamEvents(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **types** | `Set<EventType>` | Only these event types (plus &#x60;hello&#x60;). Default - all. | [Optional] |

### Return type

**string**

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `text/event-stream`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The event stream (never completes normally). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

