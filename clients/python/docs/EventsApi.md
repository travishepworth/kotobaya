# kotobaya_client.EventsApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**stream_events**](EventsApi.md#stream_events) | **GET** /events | Server-sent events - job progress, sync activity, new notes, device changes.


# **stream_events**
> str stream_events(types=types)

Server-sent events - job progress, sync activity, new notes, device changes.

A `text/event-stream` (HTML Living Standard) that stays open. Each event is

```
id: <n>
event: <type>
data: <one line of JSON: the Event object>

```

- `id` counts up from 1 per connection. There is **no replay**: `Last-Event-ID` is
  ignored. After (re)connecting, a client refetches what it shows; the first event on
  every connection is `hello` and carries `high_water_seq` for that purpose.
- `data` is always a single-line JSON object with the schema `Event`
  (`{"type", "at", "data"}`); `event:` repeats `type`.
- A comment line `: ping` is sent every 25 seconds so proxies keep the connection.
- Delivery is best effort; events may be coalesced. Slow consumers are disconnected
  when more than 1000 events are buffered for them.
- The stream ends with HTTP close when the credential is revoked or expires.

| `type` | `data` schema | Sent when |
|---|---|---|
| `hello` | `EventHello` | Once, first, on every connection. |
| `sync.changed` | `EventSyncChanged` | One or more ops were logged (any origin). Coalesced to at most one per second; carries the new `high_water_seq`. Clients with a replica pull; others refetch. |
| `note.created` | `EventNoteCreated` | A `note.created` op was applied (once per note). |
| `job.updated` | `Job` | A job was created, changed status, or its progress changed (at most one per job per second while running; the terminal state is always sent). Never sent for a server-internal job (C-9, see `Job`); its progress shows up as `job.updated` of its parent. |
| `device.paired` | `EventDevice` | `POST /devices/pair` succeeded. |
| `device.revoked` | `EventDevice` | A device was revoked. |

`types` filters the stream (comma-separated); `hello` is always sent.

**ntfy forwarding.** The server posts the same `Event` JSON as the message body to the
`push_topic` of every non-revoked device for: `job.updated` with a terminal status
(except kind `grammar-detect`, C-13: one rule edit can end hundreds of them), and
`sync.changed` (coalesced to at most one per minute per device, and not to the device
that originated the change).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.event_type import EventType
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.EventsApi(api_client)
    types = [kotobaya_client.EventType()] # List[EventType] | Only these event types (plus `hello`). Default - all. (optional)

    try:
        # Server-sent events - job progress, sync activity, new notes, device changes.
        api_response = api_instance.stream_events(types=types)
        print("The response of EventsApi->stream_events:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling EventsApi->stream_events: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **types** | [**List[EventType]**](EventType.md)| Only these event types (plus &#x60;hello&#x60;). Default - all. | [optional] 

### Return type

**str**

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: text/event-stream, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The event stream (never completes normally). |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

