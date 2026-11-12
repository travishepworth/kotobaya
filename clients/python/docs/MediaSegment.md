# MediaSegment

An episode, chapter, volume or page (`app.media_segment`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Segment id (stable across re-parses). | 
**kind** | **str** | Segment kind. | 
**media_id** | **str** | Owning media. | 
**ordinal** | **int** | 0-based order within the media. | 
**sentence_count** | **int** | Sentences in the segment. | 
**title** | **str** | Title; may be empty. | 
**token_count** | **int** | Word tokens in the segment. | 
**unmapped_token_count** | **int** | Of those, tokens that matched no entry. | 

## Example

```python
from kotobaya_client.models.media_segment import MediaSegment

# TODO update the JSON string below
json = "{}"
# create an instance of MediaSegment from a JSON string
media_segment_instance = MediaSegment.from_json(json)
# print the JSON string representation of the object
print(MediaSegment.to_json())

# convert the object into a dict
media_segment_dict = media_segment_instance.to_dict()
# create an instance of MediaSegment from a dict
media_segment_from_dict = MediaSegment.from_dict(media_segment_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


