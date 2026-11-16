# ShellApi

All URIs are relative to */shell/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getShellInfo**](ShellApi.md#getshellinfo) | **GET** /info | What this shell is and can do. |
| [**getSyncStatus**](ShellApi.md#getsyncstatus) | **GET** /sync | Sync status of this device. |
| [**pairShell**](ShellApi.md#pairshell) | **POST** /pair | Pair this install with a server. |
| [**syncNow**](ShellApi.md#syncnow) | **POST** /sync | Start a sync cycle now. |
| [**unpairShell**](ShellApi.md#unpairshell) | **POST** /unpair | Forget the device token. |



## getShellInfo

> ShellInfo getShellInfo()

What this shell is and can do.

The first call the SPA makes after it sees &#x60;window.KotobayaShell&#x60;. Works unpaired, never waits for the readiness gate (SHELL.md 2.5) and never answers &#x60;shell_rebuilding&#x60;. Reads nothing from the replica. The UI gates features on &#x60;capabilities&#x60;, never on \&quot;is Android\&quot;. 

### Example

```ts
import {
  Configuration,
  ShellApi,
} from '@kotobaya/shell-client';
import type { GetShellInfoRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new ShellApi();

  try {
    const data = await api.getShellInfo();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**ShellInfo**](ShellInfo.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Shell information. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getSyncStatus

> ShellSyncStatus getSyncStatus()

Sync status of this device.

What the sync chip shows. Never waits for the readiness gate and never answers &#x60;shell_rebuilding&#x60; (it is how the SPA learns about a rebuild). The same object is pushed as the &#x60;shell.sync&#x60; event whenever any property changes (at most once per second, the last state always delivered), so the SPA calls this once and then listens. 

### Example

```ts
import {
  Configuration,
  ShellApi,
} from '@kotobaya/shell-client';
import type { GetSyncStatusRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new ShellApi();

  try {
    const data = await api.getSyncStatus();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**ShellSyncStatus**](ShellSyncStatus.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Current status. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## pairShell

> ShellInfo pairShell(shellPairRequest)

Pair this install with a server.

Works unpaired; never waits for the readiness gate. The shell calls &#x60;POST /devices/pair&#x60; (&#x60;pairDevice&#x60; of &#x60;../openapi.yaml&#x60;) on the given server with this install\&#39;s &#x60;device_id&#x60; (the stored &#x60;sync_state.device_id&#x60;, or a fresh UUIDv7 on a new install) and, when it has one, its &#x60;hlc_last&#x60;; stores the returned device token in the Keystore-backed &#x60;TokenStore&#x60;; and starts a sync. The token never appears in the response.  Send **either** &#x60;uri&#x60; (a &#x60;kotobaya://pair?server&#x3D;...&amp;code&#x3D;...&#x60; URI: scanned, pasted, or delivered by the deep link as &#x60;/login?pair&#x3D;&#x60;) **or** &#x60;server_url&#x60; and &#x60;code&#x60;. Sending both forms, or neither, is &#x60;422 validation_failed&#x60; (&#x60;errors[0].code &#x3D; conflicting_fields&#x60; or &#x60;required&#x60;, pointer &#x60;/uri&#x60;).  Outcomes, in check order: 1. Already paired and &#x60;auth_required&#x60; is false: &#x60;409 already_exists&#x60; with &#x60;existing_id&#x60;    &#x3D; the current &#x60;device_id&#x60;. Nothing changes; unpair first. (Paired with    &#x60;auth_required: true&#x60; - the server revoked the token - is allowed: the new token    replaces the old one for the same &#x60;device_id&#x60;, SHELL.md 9.2.) 2. Body invalid (unknown property, malformed &#x60;uri&#x60;, &#x60;server_url&#x60; not an absolute    &#x60;http&#x60;/&#x60;https&#x60; URL, code not 8 Crockford characters after removing hyphens and    spaces): &#x60;422 validation_failed&#x60;. 3. Offline, connect failure or timeout: &#x60;503 shell_offline&#x60;. 4. Any problem the server answers (&#x60;401 invalid_pairing_code&#x60;, &#x60;422&#x60; with    &#x60;hlc_regressed&#x60;, &#x60;429 rate_limited&#x60; with &#x60;Retry-After&#x60;): passed through unchanged.    On &#x60;hlc_regressed&#x60; the shell does not retry with a new identity by itself. 5. &#x60;200&#x60; with the new &#x60;ShellInfo&#x60; (&#x60;paired: true&#x60;). A &#x60;shell.sync&#x60; event follows.  Not idempotent: a pairing code is single-use. A retry after a lost response sees case 1, which the SPA treats as success when &#x60;existing_id&#x60; is a paired device (it re-reads &#x60;getShellInfo&#x60;). 

### Example

```ts
import {
  Configuration,
  ShellApi,
} from '@kotobaya/shell-client';
import type { PairShellRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new ShellApi();

  const body = {
    // ShellPairRequest
    shellPairRequest: {"uri":"kotobaya://pair?server=https%3A%2F%2Fkotobaya.example.net%2Fapi%2Fv1&code=K7Q2M9XD"},
  } satisfies PairShellRequest;

  try {
    const data = await api.pairShell(body);
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
| **shellPairRequest** | [ShellPairRequest](ShellPairRequest.md) |  | |

### Return type

[**ShellInfo**](ShellInfo.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Paired. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## syncNow

> ShellSyncStatus syncNow()

Start a sync cycle now.

Asks the sync engine for a push + pull cycle and answers at once with the status at that moment (&#x60;202&#x60;); progress and the result arrive as &#x60;shell.sync&#x60; events. A cycle already running is not restarted (&#x60;202&#x60;, same status). Offline (&#x60;online: false&#x60;): &#x60;503 shell_offline&#x60;, nothing started. No request body. 

### Example

```ts
import {
  Configuration,
  ShellApi,
} from '@kotobaya/shell-client';
import type { SyncNowRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new ShellApi();

  try {
    const data = await api.syncNow();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**ShellSyncStatus**](ShellSyncStatus.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **202** | A cycle is running. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## unpairShell

> ShellInfo unpairShell(shellUnpairRequest)

Forget the device token.

Deletes the stored device token and nothing else: the local replica, the pending ops, the cached blobs, the dictionary and &#x60;sync_state.device_id&#x60; are kept, so the same intact install can pair again and push what it still holds (SYNC.md 3.3). It does not call the server (revoking the device is an &#x60;admin&#x60; operation of the web app).  - Pending ops exist (&#x60;ShellSyncStatus.pending_ops &gt; 0&#x60;) and &#x60;force&#x60; is not &#x60;true&#x60;:   &#x60;409 shell_pending_ops&#x60;; nothing is deleted. The SPA asks the user and repeats with   &#x60;force: true&#x60;, or syncs first. - Already unpaired: &#x60;401 unauthenticated&#x60;, like every operation except &#x60;getShellInfo&#x60; and   &#x60;pairShell&#x60;. A retry after a lost response therefore sees &#x60;401&#x60;, which the SPA treats   as \&quot;unpaired\&quot; (it re-reads &#x60;getShellInfo&#x60;). - Otherwise &#x60;200&#x60; with &#x60;paired: false&#x60;; a &#x60;shell.sync&#x60; event follows and every later   &#x60;/api/v1&#x60; request answers &#x60;401 unauthenticated&#x60;.  The body may be omitted (&#x60;body: null&#x60;), which means &#x60;{\&quot;force\&quot;: false}&#x60;. 

### Example

```ts
import {
  Configuration,
  ShellApi,
} from '@kotobaya/shell-client';
import type { UnpairShellRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new ShellApi();

  const body = {
    // ShellUnpairRequest (optional)
    shellUnpairRequest: {"force":true},
  } satisfies UnpairShellRequest;

  try {
    const data = await api.unpairShell(body);
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
| **shellUnpairRequest** | [ShellUnpairRequest](ShellUnpairRequest.md) |  | [Optional] |

### Return type

[**ShellInfo**](ShellInfo.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Unpaired. |  -  |
| **409** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

