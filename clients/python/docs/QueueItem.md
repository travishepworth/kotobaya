# QueueItem

One card to show, with everything needed to render and grade it.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**card** | [**Card**](Card.md) | The card, with &#x60;note&#x60;. | 
**entry** | [**DictEntry**](DictEntry.md) | The note&#39;s dictionary entry, or null (no reference, or entry missing). | 
**kanji** | [**Kanji**](Kanji.md) | For a note of base type &#x60;kanji&#x60;, the &#x60;ref.kanji&#x60; row of its &#x60;kanji_literal&#x60; (C-2); null for other notes or when the kanji is not in &#x60;ref.kanji&#x60;. The client passes &#x60;literal&#x60;, &#x60;meanings&#x60;, &#x60;on_readings&#x60; (onyomi), &#x60;kun_readings&#x60; (kunyomi) and &#x60;stroke_count&#x60; to the core renderer as &#x60;RenderInput.kanji&#x60; / &#x60;JsRenderInput.kanji&#x60;, so a kanji note with empty fields (&#x60;auto_kanji&#x60;, &#x60;jpdb_import&#x60;) renders from it identically on every host. Absent is treated as null. | [optional] 
**preview** | [**SchedulePreview**](SchedulePreview.md) | Null for drill and cram items. | 
**review_type** | **str** | Review type an answer will be logged with. | 
**sentence** | [**Sentence**](Sentence.md) | Example sentence chosen for this showing (grammar cloze and other rotating-example kinds); send its id back as &#x60;sentence_id&#x60;. Null otherwise. | 

## Example

```python
from kotobaya_client.models.queue_item import QueueItem

# TODO update the JSON string below
json = "{}"
# create an instance of QueueItem from a JSON string
queue_item_instance = QueueItem.from_json(json)
# print the JSON string representation of the object
print(QueueItem.to_json())

# convert the object into a dict
queue_item_dict = queue_item_instance.to_dict()
# create an instance of QueueItem from a dict
queue_item_from_dict = QueueItem.from_dict(queue_item_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


