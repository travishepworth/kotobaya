# kotobaya_client.StatsApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**get_stats_calibration**](StatsApi.md#get_stats_calibration) | **GET** /stats/calibration | FSRS calibration - predicted versus actual recall.
[**get_stats_forecast**](StatsApi.md#get_stats_forecast) | **GET** /stats/forecast | Due forecast per study day, plus the next 24 hours by hour.
[**get_stats_heatmap**](StatsApi.md#get_stats_heatmap) | **GET** /stats/heatmap | Reviews per study day.
[**get_stats_known_words**](StatsApi.md#get_stats_known_words) | **GET** /stats/known-words | Known words over time.
[**get_stats_retention**](StatsApi.md#get_stats_retention) | **GET** /stats/retention | Retention (share of reviews not rated Again), grouped.
[**get_stats_summary**](StatsApi.md#get_stats_summary) | **GET** /stats/summary | Today&#39;s counts for home screens (due, new, done, time).


# **get_stats_calibration**
> StatsCalibration get_stats_calibration(var_from=var_from, to=to, preset_id=preset_id)

FSRS calibration - predicted versus actual recall.

Over `scheduled` reviews (not undone) in `[from, to]`: each review's predicted
retrievability (from the replay state just before it) is put into one of 20 equal bins
over [0, 1]; a bin reports the mean prediction, the actual pass rate and the count.
Empty bins are omitted. Optional `preset_id` restricts to cards on that preset.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.stats_calibration import StatsCalibration
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
    api_instance = kotobaya_client.StatsApi(api_client)
    var_from = '2026-01-01' # date | First study day, inclusive. Default - `to` minus 364 days. (optional)
    to = '2026-11-12' # date | Last study day, inclusive. Default - the current study day. (optional)
    preset_id = '00000000-0000-7000-8000-000000000010' # str | Only reviews of cards currently on this preset. (optional)

    try:
        # FSRS calibration - predicted versus actual recall.
        api_response = api_instance.get_stats_calibration(var_from=var_from, to=to, preset_id=preset_id)
        print("The response of StatsApi->get_stats_calibration:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling StatsApi->get_stats_calibration: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **var_from** | **date**| First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [optional] 
 **to** | **date**| Last study day, inclusive. Default - the current study day. | [optional] 
 **preset_id** | **str**| Only reviews of cards currently on this preset. | [optional] 

### Return type

[**StatsCalibration**](StatsCalibration.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Calibration bins. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_stats_forecast**
> StatsForecast get_stats_forecast(days=days)

Due forecast per study day, plus the next 24 hours by hour.

`days[0]` is the current study day and includes overdue cards. Counts live, unsuspended
cards by `due_at`; no simulation of future reviews. `hours` has 24 elements starting at
the current clock hour (UTC instants), cards due in each hour; overdue cards are in
`hours[0]`. New cards are not forecast.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.stats_forecast import StatsForecast
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
    api_instance = kotobaya_client.StatsApi(api_client)
    days = 30 # int | Number of study days to forecast, starting today. (optional) (default to 30)

    try:
        # Due forecast per study day, plus the next 24 hours by hour.
        api_response = api_instance.get_stats_forecast(days=days)
        print("The response of StatsApi->get_stats_forecast:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling StatsApi->get_stats_forecast: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **days** | **int**| Number of study days to forecast, starting today. | [optional] [default to 30]

### Return type

[**StatsForecast**](StatsForecast.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The forecast. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_stats_heatmap**
> StatsHeatmap get_stats_heatmap(var_from=var_from, to=to)

Reviews per study day.

One element per study day in `[from, to]` that has at least one review; days without
reviews are omitted. Counts exclude undone and `manual` rows. A review's study day is
computed from `reviewed_at` with the row's `utc_offset_min` (falling back to
`scheduler.timezone` when NULL) and `scheduler.day_rollover_hour`. `from` after `to` is
`422`; the range may span at most 3660 days.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.stats_heatmap import StatsHeatmap
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
    api_instance = kotobaya_client.StatsApi(api_client)
    var_from = '2026-01-01' # date | First study day, inclusive. Default - `to` minus 364 days. (optional)
    to = '2026-11-12' # date | Last study day, inclusive. Default - the current study day. (optional)

    try:
        # Reviews per study day.
        api_response = api_instance.get_stats_heatmap(var_from=var_from, to=to)
        print("The response of StatsApi->get_stats_heatmap:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling StatsApi->get_stats_heatmap: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **var_from** | **date**| First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [optional] 
 **to** | **date**| Last study day, inclusive. Default - the current study day. | [optional] 

### Return type

[**StatsHeatmap**](StatsHeatmap.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Per-day review counts. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_stats_known_words**
> StatsKnownWords get_stats_known_words(var_from=var_from, to=to, interval=interval)

Known words over time.

`current` counts `word_status` rows by effective status now. `series` is a best-effort
reconstruction: for each period end, the number of entries whose vocab note had reached
the core's "known" threshold by then according to the review log, plus overrides by
their `updated_at`. Only the latest point is guaranteed to equal `current.known +
current.never_forget`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.stats_known_words import StatsKnownWords
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
    api_instance = kotobaya_client.StatsApi(api_client)
    var_from = '2026-01-01' # date | First study day, inclusive. Default - `to` minus 364 days. (optional)
    to = '2026-11-12' # date | Last study day, inclusive. Default - the current study day. (optional)
    interval = 'week' # str | Spacing of the series points. (optional)

    try:
        # Known words over time.
        api_response = api_instance.get_stats_known_words(var_from=var_from, to=to, interval=interval)
        print("The response of StatsApi->get_stats_known_words:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling StatsApi->get_stats_known_words: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **var_from** | **date**| First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [optional] 
 **to** | **date**| Last study day, inclusive. Default - the current study day. | [optional] 
 **interval** | **str**| Spacing of the series points. | [optional] 

### Return type

[**StatsKnownWords**](StatsKnownWords.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Known-word counts. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_stats_retention**
> StatsRetention get_stats_retention(var_from=var_from, to=to, group_by=group_by)

Retention (share of reviews not rated Again), grouped.

Over `scheduled` reviews (not `learning`, `drill`, `cram`, `manual`; not undone) whose
study day is in `[from, to]`. `retention` = passed / total where passed = rating 2-4.
Groups with zero reviews are omitted. `young` = interval before the review under 21
days, `mature` = 21 days or more.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.stats_retention import StatsRetention
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
    api_instance = kotobaya_client.StatsApi(api_client)
    var_from = '2026-01-01' # date | First study day, inclusive. Default - `to` minus 364 days. (optional)
    to = '2026-11-12' # date | Last study day, inclusive. Default - the current study day. (optional)
    group_by = 'card_kind' # str | Grouping key. `card_kind` groups by `(note type base_type, card kind)`; `day`, `week` (ISO, Monday start) and `month` group by study day. (optional)

    try:
        # Retention (share of reviews not rated Again), grouped.
        api_response = api_instance.get_stats_retention(var_from=var_from, to=to, group_by=group_by)
        print("The response of StatsApi->get_stats_retention:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling StatsApi->get_stats_retention: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **var_from** | **date**| First study day, inclusive. Default - &#x60;to&#x60; minus 364 days. | [optional] 
 **to** | **date**| Last study day, inclusive. Default - the current study day. | [optional] 
 **group_by** | **str**| Grouping key. &#x60;card_kind&#x60; groups by &#x60;(note type base_type, card kind)&#x60;; &#x60;day&#x60;, &#x60;week&#x60; (ISO, Monday start) and &#x60;month&#x60; group by study day. | [optional] 

### Return type

[**StatsRetention**](StatsRetention.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Retention per group and overall. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_stats_summary**
> StatsSummary get_stats_summary()

Today's counts for home screens (due, new, done, time).

Computed at request time for the current study day with the core's queue builder, so the numbers equal what `GET /review/queue` would serve.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.stats_summary import StatsSummary
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
    api_instance = kotobaya_client.StatsApi(api_client)

    try:
        # Today's counts for home screens (due, new, done, time).
        api_response = api_instance.get_stats_summary()
        print("The response of StatsApi->get_stats_summary:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling StatsApi->get_stats_summary: %s\n" % e)
```



### Parameters

This endpoint does not need any parameter.

### Return type

[**StatsSummary**](StatsSummary.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Today&#39;s summary. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

