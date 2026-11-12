# StatsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**getStatsCalibration**](StatsApi.md#getStatsCalibration) | **GET** /stats/calibration | FSRS calibration - predicted versus actual recall. |
| [**getStatsForecast**](StatsApi.md#getStatsForecast) | **GET** /stats/forecast | Due forecast per study day, plus the next 24 hours by hour. |
| [**getStatsHeatmap**](StatsApi.md#getStatsHeatmap) | **GET** /stats/heatmap | Reviews per study day. |
| [**getStatsKnownWords**](StatsApi.md#getStatsKnownWords) | **GET** /stats/known-words | Known words over time. |
| [**getStatsRetention**](StatsApi.md#getStatsRetention) | **GET** /stats/retention | Retention (share of reviews not rated Again), grouped. |
| [**getStatsSummary**](StatsApi.md#getStatsSummary) | **GET** /stats/summary | Today&#39;s counts for home screens (due, new, done, time). |


<a id="getStatsCalibration"></a>
# **getStatsCalibration**
> StatsCalibration getStatsCalibration(from, to, presetId)

FSRS calibration - predicted versus actual recall.

Over &#x60;scheduled&#x60; reviews (not undone) in &#x60;[from, to]&#x60;: each review&#39;s predicted retrievability (from the replay state just before it) is put into one of 20 equal bins over [0, 1]; a bin reports the mean prediction, the actual pass rate and the count. Empty bins are omitted. Optional &#x60;preset_id&#x60; restricts to cards on that preset. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = StatsApi()
val from : java.time.LocalDate = 2026-01-01 // java.time.LocalDate | First study day, inclusive. Default - `to` minus 364 days.
val to : java.time.LocalDate = 2026-11-12 // java.time.LocalDate | Last study day, inclusive. Default - the current study day.
val presetId : kotlin.String = 00000000-0000-7000-8000-000000000010 // kotlin.String | Only reviews of cards currently on this preset.
try {
    val result : StatsCalibration = apiInstance.getStatsCalibration(from, to, presetId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling StatsApi#getStatsCalibration")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling StatsApi#getStatsCalibration")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **from** | **java.time.LocalDate**| First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [optional] |
| **to** | **java.time.LocalDate**| Last study day, inclusive. Default - the current study day. | [optional] |
| **presetId** | **kotlin.String**| Only reviews of cards currently on this preset. | [optional] |

### Return type

[**StatsCalibration**](StatsCalibration.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="getStatsForecast"></a>
# **getStatsForecast**
> StatsForecast getStatsForecast(days)

Due forecast per study day, plus the next 24 hours by hour.

&#x60;days[0]&#x60; is the current study day and includes overdue cards. Counts live, unsuspended cards by &#x60;due_at&#x60;; no simulation of future reviews. &#x60;hours&#x60; has 24 elements starting at the current clock hour (UTC instants), cards due in each hour; overdue cards are in &#x60;hours[0]&#x60;. New cards are not forecast. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = StatsApi()
val days : kotlin.Int = 30 // kotlin.Int | Number of study days to forecast, starting today.
try {
    val result : StatsForecast = apiInstance.getStatsForecast(days)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling StatsApi#getStatsForecast")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling StatsApi#getStatsForecast")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **days** | **kotlin.Int**| Number of study days to forecast, starting today. | [optional] [default to 30] |

### Return type

[**StatsForecast**](StatsForecast.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="getStatsHeatmap"></a>
# **getStatsHeatmap**
> StatsHeatmap getStatsHeatmap(from, to)

Reviews per study day.

One element per study day in &#x60;[from, to]&#x60; that has at least one review; days without reviews are omitted. Counts exclude undone and &#x60;manual&#x60; rows. A review&#39;s study day is computed from &#x60;reviewed_at&#x60; with the row&#39;s &#x60;utc_offset_min&#x60; (falling back to &#x60;scheduler.timezone&#x60; when NULL) and &#x60;scheduler.day_rollover_hour&#x60;. &#x60;from&#x60; after &#x60;to&#x60; is &#x60;422&#x60;; the range may span at most 3660 days. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = StatsApi()
val from : java.time.LocalDate = 2026-01-01 // java.time.LocalDate | First study day, inclusive. Default - `to` minus 364 days.
val to : java.time.LocalDate = 2026-11-12 // java.time.LocalDate | Last study day, inclusive. Default - the current study day.
try {
    val result : StatsHeatmap = apiInstance.getStatsHeatmap(from, to)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling StatsApi#getStatsHeatmap")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling StatsApi#getStatsHeatmap")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **from** | **java.time.LocalDate**| First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [optional] |
| **to** | **java.time.LocalDate**| Last study day, inclusive. Default - the current study day. | [optional] |

### Return type

[**StatsHeatmap**](StatsHeatmap.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="getStatsKnownWords"></a>
# **getStatsKnownWords**
> StatsKnownWords getStatsKnownWords(from, to, interval)

Known words over time.

&#x60;current&#x60; counts &#x60;word_status&#x60; rows by effective status now. &#x60;series&#x60; is a best-effort reconstruction: for each period end, the number of entries whose vocab note had reached the core&#39;s \&quot;known\&quot; threshold by then according to the review log, plus overrides by their &#x60;updated_at&#x60;. Only the latest point is guaranteed to equal &#x60;current.known + current.never_forget&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = StatsApi()
val from : java.time.LocalDate = 2026-01-01 // java.time.LocalDate | First study day, inclusive. Default - `to` minus 364 days.
val to : java.time.LocalDate = 2026-11-12 // java.time.LocalDate | Last study day, inclusive. Default - the current study day.
val interval : kotlin.String = week // kotlin.String | Spacing of the series points.
try {
    val result : StatsKnownWords = apiInstance.getStatsKnownWords(from, to, interval)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling StatsApi#getStatsKnownWords")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling StatsApi#getStatsKnownWords")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **from** | **java.time.LocalDate**| First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [optional] |
| **to** | **java.time.LocalDate**| Last study day, inclusive. Default - the current study day. | [optional] |
| **interval** | **kotlin.String**| Spacing of the series points. | [optional] [enum: day, week, month] |

### Return type

[**StatsKnownWords**](StatsKnownWords.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="getStatsRetention"></a>
# **getStatsRetention**
> StatsRetention getStatsRetention(from, to, groupBy)

Retention (share of reviews not rated Again), grouped.

Over &#x60;scheduled&#x60; reviews (not &#x60;learning&#x60;, &#x60;drill&#x60;, &#x60;cram&#x60;, &#x60;manual&#x60;; not undone) whose study day is in &#x60;[from, to]&#x60;. &#x60;retention&#x60; &#x3D; passed / total where passed &#x3D; rating 2-4. Groups with zero reviews are omitted. &#x60;young&#x60; &#x3D; interval before the review under 21 days, &#x60;mature&#x60; &#x3D; 21 days or more. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = StatsApi()
val from : java.time.LocalDate = 2026-01-01 // java.time.LocalDate | First study day, inclusive. Default - `to` minus 364 days.
val to : java.time.LocalDate = 2026-11-12 // java.time.LocalDate | Last study day, inclusive. Default - the current study day.
val groupBy : kotlin.String = card_kind // kotlin.String | Grouping key. `card_kind` groups by `(note type base_type, card kind)`; `day`, `week` (ISO, Monday start) and `month` group by study day.
try {
    val result : StatsRetention = apiInstance.getStatsRetention(from, to, groupBy)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling StatsApi#getStatsRetention")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling StatsApi#getStatsRetention")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **from** | **java.time.LocalDate**| First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [optional] |
| **to** | **java.time.LocalDate**| Last study day, inclusive. Default - the current study day. | [optional] |
| **groupBy** | **kotlin.String**| Grouping key. &#x60;card_kind&#x60; groups by &#x60;(note type base_type, card kind)&#x60;; &#x60;day&#x60;, &#x60;week&#x60; (ISO, Monday start) and &#x60;month&#x60; group by study day. | [optional] [enum: card_kind, day, week, month] |

### Return type

[**StatsRetention**](StatsRetention.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="getStatsSummary"></a>
# **getStatsSummary**
> StatsSummary getStatsSummary()

Today&#39;s counts for home screens (due, new, done, time).

Computed at request time for the current study day with the core&#39;s queue builder, so the numbers equal what &#x60;GET /review/queue&#x60; would serve.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = StatsApi()
try {
    val result : StatsSummary = apiInstance.getStatsSummary()
    println(result)
} catch (e: ClientException) {
    println("4xx response calling StatsApi#getStatsSummary")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling StatsApi#getStatsSummary")
    e.printStackTrace()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

[**StatsSummary**](StatsSummary.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

