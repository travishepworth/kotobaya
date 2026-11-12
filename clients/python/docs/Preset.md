# Preset

A scheduling preset (`app.fsrs_params`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**created_at** | **datetime** | Creation time. | 
**deleted_at** | **datetime** | Tombstone. | 
**desired_retention** | **float** | Target recall probability. | 
**easy_days** | **List[float]** | Load multiplier per ISO weekday, element 0 &#x3D; Monday ... 6 &#x3D; Sunday (the SQL array index 1..7). 1 &#x3D; normal, 0 &#x3D; minimum. | 
**fsrs_version** | **str** | FSRS version of &#x60;weights&#x60;; null together with &#x60;weights&#x60;. | 
**fuzz** | **bool** | Whether interval fuzz and load balancing are applied. | 
**id** | **str** | Preset id. &#x60;00000000-0000-7000-8000-000000000010&#x60; is the Default preset. | 
**learning_steps_s** | **List[int]** | Learning steps in seconds, in order; empty &#x3D; none. | 
**maximum_interval_days** | **int** | Cap on scheduled intervals. | 
**name** | **str** | Display name. | 
**optimized_at** | **datetime** | When weights were last published. | 
**optimizer_stats** | **Dict[str, object]** | Metrics of the last optimizer run: &#x60;{\&quot;review_count\&quot;, \&quot;log_loss\&quot;, \&quot;rmse_bins\&quot;}&#x60;. | 
**relearning_steps_s** | **List[int]** | Relearning steps in seconds. | 
**updated_at** | **datetime** | Last change. | 
**weights** | **List[float]** | Optimized FSRS weights, or null &#x3D; the core&#39;s defaults. | 
**weights_version** | **int** | Incremented by each optimizer publication; 0 &#x3D; never optimized. | 

## Example

```python
from kotobaya_client.models.preset import Preset

# TODO update the JSON string below
json = "{}"
# create an instance of Preset from a JSON string
preset_instance = Preset.from_json(json)
# print the JSON string representation of the object
print(Preset.to_json())

# convert the object into a dict
preset_dict = preset_instance.to_dict()
# create an instance of Preset from a dict
preset_from_dict = Preset.from_dict(preset_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


