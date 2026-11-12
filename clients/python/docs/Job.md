# Job

A background job (`app.job`). `payload` and `result` depend on `kind`:  | `kind` | `payload` schema | `result` schema (when `succeeded`) | |---|---|---| | `dict-refresh` | `JobPayloadDictRefresh` | `JobResultDictRefresh` | | `media-import` | `MediaImportRequest` (with `media_id`) | `JobResultMedia` | | `media-parse` | `JobPayloadMediaParse` | `JobResultMedia` | | `jpdb-deck-import` | `JpdbDeckImportRequest` (with `media_id`) | `JobResultMedia` | | `jpdb-history-import` | `JpdbHistoryImportRequest` | `JobResultJpdbHistory` | | `fsrs-optimize` | `JobPayloadFsrsOptimize` | `JobResultFsrsOptimize` | | `tts` | `TtsRequest` | `JobResultTts` | | `deck-build` | `JobPayloadDeckBuild` | `JobResultDeckBuild` | 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**attempts** | **int** | Attempts started so far. | 
**created_at** | **datetime** | When it was queued. | 
**error** | **str** | Error text of the last failed attempt. | 
**finished_at** | **datetime** | Set iff the status is terminal. | 
**id** | **str** | Job id (server-generated UUIDv7). | 
**kind** | [**JobKind**](JobKind.md) |  | 
**max_attempts** | **int** | Attempts allowed before the job is &#x60;failed&#x60;. | 
**payload** | **Dict[str, object]** | Input of the job; schema by &#x60;kind&#x60;. | 
**progress** | **float** | Fraction done; 1 when succeeded. | 
**progress_message** | **str** | Short human-readable stage, e.g. &#x60;parsing ep02.srt (2/12)&#x60;. | 
**result** | **Dict[str, object]** | Output when &#x60;succeeded&#x60; (schema by &#x60;kind&#x60;); may hold partial counts when &#x60;failed&#x60; or &#x60;cancelled&#x60;; null before. | 
**started_at** | **datetime** | Start of the current or last attempt. | 
**status** | [**JobStatus**](JobStatus.md) |  | 

## Example

```python
from kotobaya_client.models.job import Job

# TODO update the JSON string below
json = "{}"
# create an instance of Job from a JSON string
job_instance = Job.from_json(json)
# print the JSON string representation of the object
print(Job.to_json())

# convert the object into a dict
job_dict = job_instance.to_dict()
# create an instance of Job from a dict
job_from_dict = Job.from_dict(job_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


