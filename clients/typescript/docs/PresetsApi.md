# PresetsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createPreset**](PresetsApi.md#createpreset) | **POST** /presets | Create a preset. |
| [**deletePreset**](PresetsApi.md#deletepreset) | **DELETE** /presets/{preset_id} | Delete a preset. |
| [**getPreset**](PresetsApi.md#getpreset) | **GET** /presets/{preset_id} | One preset. |
| [**listPresets**](PresetsApi.md#listpresets) | **GET** /presets | List scheduling presets. |
| [**optimizePreset**](PresetsApi.md#optimizepreset) | **POST** /presets/{preset_id}/optimize | Queue an FSRS optimizer run for this preset. |
| [**updatePreset**](PresetsApi.md#updatepreset) | **PATCH** /presets/{preset_id} | Change preset options. |



## createPreset

> Preset createPreset(presetCreate)

Create a preset.

Server emits &#x60;fsrs_params.created&#x60;. Omitted properties take the column defaults. Weights cannot be set through the API; they are published by the optimizer job. 

### Example

```ts
import {
  Configuration,
  PresetsApi,
} from '@kotobaya/api-client';
import type { CreatePresetRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PresetsApi(config);

  const body = {
    // PresetCreate
    presetCreate: {"desired_retention":0.85,"learning_steps_s":[60,600,3600],"name":"Kanji"},
  } satisfies CreatePresetRequest;

  try {
    const data = await api.createPreset(body);
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
| **presetCreate** | [PresetCreate](PresetCreate.md) |  | |

### Return type

[**Preset**](Preset.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | A preset with this &#x60;id&#x60; already existed; returned unchanged. |  -  |
| **201** | Created. |  * Kotobaya-Seq -  <br>  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## deletePreset

> deletePreset(presetId)

Delete a preset.

Server emits &#x60;fsrs_params.deleted&#x60;. Cards pointing at a deleted preset are scheduled with the Default preset (their &#x60;preset_id&#x60; is not rewritten). The Default preset (&#x60;00000000-0000-7000-8000-000000000010&#x60;) cannot be deleted: &#x60;409 builtin_immutable&#x60;. Already deleted: &#x60;204&#x60;, no op. 

### Example

```ts
import {
  Configuration,
  PresetsApi,
} from '@kotobaya/api-client';
import type { DeletePresetRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PresetsApi(config);

  const body = {
    // string | Preset id (`fsrs_params.id`).
    presetId: 00000000-0000-7000-8000-000000000010,
  } satisfies DeletePresetRequest;

  try {
    const data = await api.deletePreset(body);
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
| **presetId** | `string` | Preset id (&#x60;fsrs_params.id&#x60;). | [Defaults to `undefined`] |

### Return type

`void` (Empty response body)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Deleted. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getPreset

> Preset getPreset(presetId, includeDeleted)

One preset.

### Example

```ts
import {
  Configuration,
  PresetsApi,
} from '@kotobaya/api-client';
import type { GetPresetRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PresetsApi(config);

  const body = {
    // string | Preset id (`fsrs_params.id`).
    presetId: 00000000-0000-7000-8000-000000000010,
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
  } satisfies GetPresetRequest;

  try {
    const data = await api.getPreset(body);
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
| **presetId** | `string` | Preset id (&#x60;fsrs_params.id&#x60;). | [Defaults to `undefined`] |
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |

### Return type

[**Preset**](Preset.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The preset. |  -  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listPresets

> PresetList listPresets(includeDeleted)

List scheduling presets.

Not paginated. Ordered by &#x60;created_at&#x60;, then &#x60;id&#x60; (the Default preset first).

### Example

```ts
import {
  Configuration,
  PresetsApi,
} from '@kotobaya/api-client';
import type { ListPresetsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PresetsApi(config);

  const body = {
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
  } satisfies ListPresetsRequest;

  try {
    const data = await api.listPresets(body);
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
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |

### Return type

[**PresetList**](PresetList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Presets. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## optimizePreset

> Job optimizePreset(presetId)

Queue an FSRS optimizer run for this preset.

Enqueues a job of kind &#x60;fsrs-optimize&#x60; (payload &#x60;JobPayloadFsrsOptimize&#x60;, dedupe key &#x60;fsrs-optimize:&lt;preset id&gt;&#x60;). If a queued or running job with that key exists it is returned with &#x60;200&#x60; instead of creating another. When the job succeeds the worker publishes the weights: the server emits &#x60;fsrs_params.published&#x60; (not this request). The weekly schedule enqueues the same job for every live preset. 

### Example

```ts
import {
  Configuration,
  PresetsApi,
} from '@kotobaya/api-client';
import type { OptimizePresetRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PresetsApi(config);

  const body = {
    // string | Preset id (`fsrs_params.id`).
    presetId: 00000000-0000-7000-8000-000000000010,
  } satisfies OptimizePresetRequest;

  try {
    const data = await api.optimizePreset(body);
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
| **presetId** | `string` | Preset id (&#x60;fsrs_params.id&#x60;). | [Defaults to `undefined`] |

### Return type

[**Job**](Job.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | An equivalent job is already queued or running. |  -  |
| **202** | Job queued. |  -  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updatePreset

> Preset updatePreset(presetId, presetPatch)

Change preset options.

Server emits one &#x60;fsrs_params.updated&#x60; carrying the registers present in the body. Changing any option makes every card cache on the preset stale; replicas recompute (SCHEMA.md 4.2). Patching a deleted preset: &#x60;410 gone&#x60;. 

### Example

```ts
import {
  Configuration,
  PresetsApi,
} from '@kotobaya/api-client';
import type { UpdatePresetRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new PresetsApi(config);

  const body = {
    // string | Preset id (`fsrs_params.id`).
    presetId: 00000000-0000-7000-8000-000000000010,
    // PresetPatch
    presetPatch: {"easy_days":[1,1,1,1,1,1,0.5]},
  } satisfies UpdatePresetRequest;

  try {
    const data = await api.updatePreset(body);
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
| **presetId** | `string` | Preset id (&#x60;fsrs_params.id&#x60;). | [Defaults to `undefined`] |
| **presetPatch** | [PresetPatch](PresetPatch.md) |  | |

### Return type

[**Preset**](Preset.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated preset. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

