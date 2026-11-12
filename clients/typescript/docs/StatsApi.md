# StatsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getStatsCalibration**](StatsApi.md#getstatscalibration) | **GET** /stats/calibration | FSRS calibration - predicted versus actual recall. |
| [**getStatsForecast**](StatsApi.md#getstatsforecast) | **GET** /stats/forecast | Due forecast per study day, plus the next 24 hours by hour. |
| [**getStatsHeatmap**](StatsApi.md#getstatsheatmap) | **GET** /stats/heatmap | Reviews per study day. |
| [**getStatsKnownWords**](StatsApi.md#getstatsknownwords) | **GET** /stats/known-words | Known words over time. |
| [**getStatsRetention**](StatsApi.md#getstatsretention) | **GET** /stats/retention | Retention (share of reviews not rated Again), grouped. |
| [**getStatsSummary**](StatsApi.md#getstatssummary) | **GET** /stats/summary | Today\&#39;s counts for home screens (due, new, done, time). |



## getStatsCalibration

> StatsCalibration getStatsCalibration(from, to, presetId)

FSRS calibration - predicted versus actual recall.

Over &#x60;scheduled&#x60; reviews (not undone) in &#x60;[from, to]&#x60;: each review\&#39;s predicted retrievability (from the replay state just before it) is put into one of 20 equal bins over [0, 1]; a bin reports the mean prediction, the actual pass rate and the count. Empty bins are omitted. Optional &#x60;preset_id&#x60; restricts to cards on that preset. 

### Example

```ts
import {
  Configuration,
  StatsApi,
} from '@kotobaya/api-client';
import type { GetStatsCalibrationRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new StatsApi(config);

  const body = {
    // Date | First study day, inclusive. Default - `to` minus 364 days. (optional)
    from: 2026-01-01,
    // Date | Last study day, inclusive. Default - the current study day. (optional)
    to: 2026-11-12,
    // string | Only reviews of cards currently on this preset. (optional)
    presetId: 00000000-0000-7000-8000-000000000010,
  } satisfies GetStatsCalibrationRequest;

  try {
    const data = await api.getStatsCalibration(body);
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
| **from** | `Date` | First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [Optional] [Defaults to `undefined`] |
| **to** | `Date` | Last study day, inclusive. Default - the current study day. | [Optional] [Defaults to `undefined`] |
| **presetId** | `string` | Only reviews of cards currently on this preset. | [Optional] [Defaults to `undefined`] |

### Return type

[**StatsCalibration**](StatsCalibration.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Calibration bins. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getStatsForecast

> StatsForecast getStatsForecast(days)

Due forecast per study day, plus the next 24 hours by hour.

&#x60;days[0]&#x60; is the current study day and includes overdue cards. Counts live, unsuspended cards by &#x60;due_at&#x60;; no simulation of future reviews. &#x60;hours&#x60; has 24 elements starting at the current clock hour (UTC instants), cards due in each hour; overdue cards are in &#x60;hours[0]&#x60;. New cards are not forecast. 

### Example

```ts
import {
  Configuration,
  StatsApi,
} from '@kotobaya/api-client';
import type { GetStatsForecastRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new StatsApi(config);

  const body = {
    // number | Number of study days to forecast, starting today. (optional)
    days: 30,
  } satisfies GetStatsForecastRequest;

  try {
    const data = await api.getStatsForecast(body);
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
| **days** | `number` | Number of study days to forecast, starting today. | [Optional] [Defaults to `30`] |

### Return type

[**StatsForecast**](StatsForecast.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The forecast. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getStatsHeatmap

> StatsHeatmap getStatsHeatmap(from, to)

Reviews per study day.

One element per study day in &#x60;[from, to]&#x60; that has at least one review; days without reviews are omitted. Counts exclude undone and &#x60;manual&#x60; rows. A review\&#39;s study day is computed from &#x60;reviewed_at&#x60; with the row\&#39;s &#x60;utc_offset_min&#x60; (falling back to &#x60;scheduler.timezone&#x60; when NULL) and &#x60;scheduler.day_rollover_hour&#x60;. &#x60;from&#x60; after &#x60;to&#x60; is &#x60;422&#x60;; the range may span at most 3660 days. 

### Example

```ts
import {
  Configuration,
  StatsApi,
} from '@kotobaya/api-client';
import type { GetStatsHeatmapRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new StatsApi(config);

  const body = {
    // Date | First study day, inclusive. Default - `to` minus 364 days. (optional)
    from: 2026-01-01,
    // Date | Last study day, inclusive. Default - the current study day. (optional)
    to: 2026-11-12,
  } satisfies GetStatsHeatmapRequest;

  try {
    const data = await api.getStatsHeatmap(body);
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
| **from** | `Date` | First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [Optional] [Defaults to `undefined`] |
| **to** | `Date` | Last study day, inclusive. Default - the current study day. | [Optional] [Defaults to `undefined`] |

### Return type

[**StatsHeatmap**](StatsHeatmap.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Per-day review counts. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getStatsKnownWords

> StatsKnownWords getStatsKnownWords(from, to, interval)

Known words over time.

&#x60;current&#x60; counts &#x60;word_status&#x60; rows by effective status now. &#x60;series&#x60; is a best-effort reconstruction: for each period end, the number of entries whose vocab note had reached the core\&#39;s \&quot;known\&quot; threshold by then according to the review log, plus overrides by their &#x60;updated_at&#x60;. Only the latest point is guaranteed to equal &#x60;current.known + current.never_forget&#x60;. 

### Example

```ts
import {
  Configuration,
  StatsApi,
} from '@kotobaya/api-client';
import type { GetStatsKnownWordsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new StatsApi(config);

  const body = {
    // Date | First study day, inclusive. Default - `to` minus 364 days. (optional)
    from: 2026-01-01,
    // Date | Last study day, inclusive. Default - the current study day. (optional)
    to: 2026-11-12,
    // 'day' | 'week' | 'month' | Spacing of the series points. (optional)
    interval: week,
  } satisfies GetStatsKnownWordsRequest;

  try {
    const data = await api.getStatsKnownWords(body);
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
| **from** | `Date` | First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [Optional] [Defaults to `undefined`] |
| **to** | `Date` | Last study day, inclusive. Default - the current study day. | [Optional] [Defaults to `undefined`] |
| **interval** | `day`, `week`, `month` | Spacing of the series points. | [Optional] [Defaults to `undefined`] [Enum: day, week, month] |

### Return type

[**StatsKnownWords**](StatsKnownWords.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Known-word counts. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getStatsRetention

> StatsRetention getStatsRetention(from, to, groupBy)

Retention (share of reviews not rated Again), grouped.

Over &#x60;scheduled&#x60; reviews (not &#x60;learning&#x60;, &#x60;drill&#x60;, &#x60;cram&#x60;, &#x60;manual&#x60;; not undone) whose study day is in &#x60;[from, to]&#x60;. &#x60;retention&#x60; &#x3D; passed / total where passed &#x3D; rating 2-4. Groups with zero reviews are omitted. &#x60;young&#x60; &#x3D; interval before the review under 21 days, &#x60;mature&#x60; &#x3D; 21 days or more. 

### Example

```ts
import {
  Configuration,
  StatsApi,
} from '@kotobaya/api-client';
import type { GetStatsRetentionRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new StatsApi(config);

  const body = {
    // Date | First study day, inclusive. Default - `to` minus 364 days. (optional)
    from: 2026-01-01,
    // Date | Last study day, inclusive. Default - the current study day. (optional)
    to: 2026-11-12,
    // 'card_kind' | 'day' | 'week' | 'month' | Grouping key. `card_kind` groups by `(note type base_type, card kind)`; `day`, `week` (ISO, Monday start) and `month` group by study day. (optional)
    groupBy: card_kind,
  } satisfies GetStatsRetentionRequest;

  try {
    const data = await api.getStatsRetention(body);
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
| **from** | `Date` | First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [Optional] [Defaults to `undefined`] |
| **to** | `Date` | Last study day, inclusive. Default - the current study day. | [Optional] [Defaults to `undefined`] |
| **groupBy** | `card_kind`, `day`, `week`, `month` | Grouping key. &#x60;card_kind&#x60; groups by &#x60;(note type base_type, card kind)&#x60;; &#x60;day&#x60;, &#x60;week&#x60; (ISO, Monday start) and &#x60;month&#x60; group by study day. | [Optional] [Defaults to `undefined`] [Enum: card_kind, day, week, month] |

### Return type

[**StatsRetention**](StatsRetention.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Retention per group and overall. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getStatsSummary

> StatsSummary getStatsSummary()

Today\&#39;s counts for home screens (due, new, done, time).

Computed at request time for the current study day with the core\&#39;s queue builder, so the numbers equal what &#x60;GET /review/queue&#x60; would serve.

### Example

```ts
import {
  Configuration,
  StatsApi,
} from '@kotobaya/api-client';
import type { GetStatsSummaryRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new StatsApi(config);

  try {
    const data = await api.getStatsSummary();
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

[**StatsSummary**](StatsSummary.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Today\&#39;s summary. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

