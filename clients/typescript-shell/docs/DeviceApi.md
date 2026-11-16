# DeviceApi

All URIs are relative to */shell/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getAnkiConnectState**](DeviceApi.md#getankiconnectstate) | **GET** /ankiconnect | On-device AnkiConnect server settings and status. |
| [**getDictInstall**](DeviceApi.md#getdictinstall) | **GET** /dict | State of the on-device dictionary package. |
| [**getDisplayPrefs**](DeviceApi.md#getdisplayprefs) | **GET** /display | Display preferences of this device (lock to portrait). |
| [**getNotificationState**](DeviceApi.md#getnotificationstate) | **GET** /notifications | Notification permission and reminder preferences. |
| [**getPushState**](DeviceApi.md#getpushstate) | **GET** /push | UnifiedPush registration state. |
| [**getStorage**](DeviceApi.md#getstorage) | **GET** /storage | Offline storage use and audio prefetch settings. |
| [**requestNotificationPermission**](DeviceApi.md#requestnotificationpermission) | **POST** /notifications/permission | Show the system notification permission dialog. |
| [**startDictInstall**](DeviceApi.md#startdictinstall) | **POST** /dict/install | Download and install (or update) the dictionary package. |
| [**updateAnkiConnectState**](DeviceApi.md#updateankiconnectstate) | **PATCH** /ankiconnect | Change the on-device AnkiConnect server settings. |
| [**updateDisplayPrefs**](DeviceApi.md#updatedisplayprefs) | **PATCH** /display | Change the display preferences. |
| [**updateNotificationPrefs**](DeviceApi.md#updatenotificationprefs) | **PATCH** /notifications | Change reminder preferences. |
| [**updatePushState**](DeviceApi.md#updatepushstate) | **PATCH** /push | Enable, retry or disable push. |
| [**updateStorage**](DeviceApi.md#updatestorage) | **PATCH** /storage | Change prefetch settings or clear the blob cache. |



## getAnkiConnectState

> ShellAnkiConnectState getAnkiConnectState()

On-device AnkiConnect server settings and status.

The loopback AnkiConnect-compatible server other apps on the phone mine into (unrelated to &#x60;POST /api/v1/ankiconnect&#x60;, which is &#x60;hidden&#x60; in the shell). 

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { GetAnkiConnectStateRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  try {
    const data = await api.getAnkiConnectState();
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

[**ShellAnkiConnectState**](ShellAnkiConnectState.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | AnkiConnect state. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getDictInstall

> ShellDictInstall getDictInstall()

State of the on-device dictionary package.

Whether a dictionary package is installed, how far a running install is, and (when the shell has gap fill) how many referenced entries the sidecar cache holds or still lacks (SHELL.md rule L-7). Pushed as the &#x60;shell.dict&#x60; event while an install runs (at most twice per second) and when it ends. 

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { GetDictInstallRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  try {
    const data = await api.getDictInstall();
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

[**ShellDictInstall**](ShellDictInstall.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Dictionary install state. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getDisplayPrefs

> ShellDisplay getDisplayPrefs()

Display preferences of this device (lock to portrait).

Device-local preferences (not synced, no op, no learning data). Offered only when &#x60;ShellInfo.capabilities&#x60; contains &#x60;display&#x60;; the SPA hides the settings row without it. **Works unpaired** (the Pair screen is rotated like the rest of the app; nothing here touches the replica or the token); during a rebuild: &#x60;503 shell_rebuilding&#x60; (as the other preference operations, SHELL.md section 12). 

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { GetDisplayPrefsRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  try {
    const data = await api.getDisplayPrefs();
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

[**ShellDisplay**](ShellDisplay.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Display preferences. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getNotificationState

> ShellNotificationState getNotificationState()

Notification permission and reminder preferences.

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { GetNotificationStateRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  try {
    const data = await api.getNotificationState();
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

[**ShellNotificationState**](ShellNotificationState.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Notification state. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getPushState

> ShellPushState getPushState()

UnifiedPush registration state.

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { GetPushStateRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  try {
    const data = await api.getPushState();
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

[**ShellPushState**](ShellPushState.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Push state. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getStorage

> ShellStorage getStorage()

Offline storage use and audio prefetch settings.

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { GetStorageRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  try {
    const data = await api.getStorage();
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

[**ShellStorage**](ShellStorage.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Storage breakdown and settings. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## requestNotificationPermission

> ShellNotificationState requestNotificationPermission()

Show the system notification permission dialog.

On Android 13+ (API 33) with the permission not granted, launches the system dialog and answers when the user has chosen, **or after 25 seconds** with the state at that moment (the dialog may still be open; the SPA reads &#x60;getNotificationState&#x60; again when its page becomes visible). On older Android, or when already granted, answers &#x60;200&#x60; at once without launching anything. When the system no longer shows the dialog (denied twice), the answer is &#x60;permission: denied&#x60; and &#x60;can_request: false&#x60;; the SPA then points the user at the system settings. No request body. Safe to repeat. 

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { RequestNotificationPermissionRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  try {
    const data = await api.requestNotificationPermission();
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

[**ShellNotificationState**](ShellNotificationState.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Notification state after the dialog (or after the 25 s limit). |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## startDictInstall

> ShellDictInstall startDictInstall(shellDictInstallRequest)

Download and install (or update) the dictionary package.

Starts the installer for the server\&#39;s current dictionary release and answers at once (&#x60;202&#x60;) with the state at that moment. While an install is running a second call starts nothing and answers &#x60;202&#x60; with the running state (so retries are safe). &#x60;scope&#x60;, when sent, is stored as the chosen package scope first. Offline: &#x60;503 shell_offline&#x60;, nothing started. The outcome is reported in &#x60;last_result&#x60; (&#x60;up_to_date&#x60; when nothing was needed). The body may be omitted. 

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { StartDictInstallRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  const body = {
    // ShellDictInstallRequest (optional)
    shellDictInstallRequest: {"scope":"all"},
  } satisfies StartDictInstallRequest;

  try {
    const data = await api.startDictInstall(body);
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
| **shellDictInstallRequest** | [ShellDictInstallRequest](ShellDictInstallRequest.md) |  | [Optional] |

### Return type

[**ShellDictInstall**](ShellDictInstall.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **202** | An install is running. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateAnkiConnectState

> ShellAnkiConnectState updateAnkiConnectState(shellAnkiConnectPatch)

Change the on-device AnkiConnect server settings.

Device-local preferences (not synced, no op). Absent properties are left unchanged. The server is restarted with the new settings before the answer; when it cannot start (port in use) the settings are still stored and the answer is &#x60;200&#x60; with &#x60;status: failed&#x60; and &#x60;status_message&#x60;. 

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { UpdateAnkiConnectStateRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  const body = {
    // ShellAnkiConnectPatch
    shellAnkiConnectPatch: {"enabled":true,"port":8765},
  } satisfies UpdateAnkiConnectStateRequest;

  try {
    const data = await api.updateAnkiConnectState(body);
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
| **shellAnkiConnectPatch** | [ShellAnkiConnectPatch](ShellAnkiConnectPatch.md) |  | |

### Return type

[**ShellAnkiConnectState**](ShellAnkiConnectState.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | AnkiConnect state after the change. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateDisplayPrefs

> ShellDisplay updateDisplayPrefs(shellDisplayPatch)

Change the display preferences.

Device-local preferences (not synced, no op). Absent properties are left unchanged; an empty object, an unknown property or a non-boolean value is &#x60;422 validation_failed&#x60; (pointer &#x60;/lock_portrait&#x60; for a wrong type, &#x60;\&quot;\&quot;&#x60; for an empty object) and changes nothing. The host applies the orientation **before** answering (SHELL.md 6.3) and stores the value in device preferences, so it survives a restart. Repeating the same request is a no-op with the same answer. Answers with the state after the change. Works unpaired; &#x60;503 shell_rebuilding&#x60; during a rebuild (nothing is changed then). 

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { UpdateDisplayPrefsRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  const body = {
    // ShellDisplayPatch
    shellDisplayPatch: {"lock_portrait":true},
  } satisfies UpdateDisplayPrefsRequest;

  try {
    const data = await api.updateDisplayPrefs(body);
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
| **shellDisplayPatch** | [ShellDisplayPatch](ShellDisplayPatch.md) |  | |

### Return type

[**ShellDisplay**](ShellDisplay.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Display preferences after the change. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateNotificationPrefs

> ShellNotificationState updateNotificationPrefs(shellNotificationPrefsPatch)

Change reminder preferences.

Device-local preferences (not synced, no op). Absent properties are left unchanged; &#x60;evening_hour: null&#x60; turns the evening reminder off. Does not request the permission: turning &#x60;nudges_enabled&#x60; on while &#x60;permission&#x60; is not &#x60;granted&#x60; is stored and has no visible effect until the permission is granted. Answers with the state after the change. 

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { UpdateNotificationPrefsRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  const body = {
    // ShellNotificationPrefsPatch
    shellNotificationPrefsPatch: {"nudges_enabled":true,"nudge_threshold":50,"evening_hour":21},
  } satisfies UpdateNotificationPrefsRequest;

  try {
    const data = await api.updateNotificationPrefs(body);
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
| **shellNotificationPrefsPatch** | [ShellNotificationPrefsPatch](ShellNotificationPrefsPatch.md) |  | |

### Return type

[**ShellNotificationState**](ShellNotificationState.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Notification state after the change. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updatePushState

> ShellPushState updatePushState(shellPushPatch)

Enable, retry or disable push.

&#x60;enabled: true&#x60; registers with the installed UnifiedPush distributor (also the \&quot;retry\&quot; action: sending it again while &#x60;state&#x60; is &#x60;waiting&#x60; registers again); &#x60;enabled: false&#x60; unregisters. The endpoint reaches the server through the shell\&#39;s own &#x60;PATCH /devices/self&#x60; when it is online; that call is not part of this operation and its failure does not fail it. Answers with the state after the change (&#x60;no_distributor&#x60; when none is installed). 

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { UpdatePushStateRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  const body = {
    // ShellPushPatch
    shellPushPatch: {"enabled":true},
  } satisfies UpdatePushStateRequest;

  try {
    const data = await api.updatePushState(body);
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
| **shellPushPatch** | [ShellPushPatch](ShellPushPatch.md) |  | |

### Return type

[**ShellPushState**](ShellPushState.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Push state after the change. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateStorage

> ShellStorage updateStorage(shellStoragePatch)

Change prefetch settings or clear the blob cache.

Device-local preferences (not synced, no op). Absent properties are left unchanged; a body with no property is &#x60;422&#x60; (&#x60;errors[0].code &#x3D; required&#x60;, &#x60;pointer&#x60; &#x60;\&quot;\&quot;&#x60;). &#x60;clear_cache: true&#x60; deletes every cached blob (card audio, media audio, covers; never the dictionary or the replica) after the other properties are stored; cached blobs are re-downloaded on demand. Answers with the state after the change. 

### Example

```ts
import {
  Configuration,
  DeviceApi,
} from '@kotobaya/shell-client';
import type { UpdateStorageRequest } from '@kotobaya/shell-client';

async function example() {
  console.log("🚀 Testing @kotobaya/shell-client SDK...");
  const api = new DeviceApi();

  const body = {
    // ShellStoragePatch
    shellStoragePatch: {"budget_mb":200,"allow_metered":false},
  } satisfies UpdateStorageRequest;

  try {
    const data = await api.updateStorage(body);
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
| **shellStoragePatch** | [ShellStoragePatch](ShellStoragePatch.md) |  | |

### Return type

[**ShellStorage**](ShellStorage.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Storage after the change. |  -  |
| **0** | Any error. &#x60;Problem&#x60; of &#x60;../openapi.yaml&#x60;; codes in that file\&#39;s error table plus the shell codes of SHELL.md section 5. &#x60;503 shell_rebuilding&#x60; carries &#x60;Retry-After: 2&#x60;.  |  * Retry-After - Seconds to wait before repeating; sent with &#x60;shell_rebuilding&#x60; (always &#x60;2&#x60;) and passed through from the server (&#x60;429&#x60;). <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

