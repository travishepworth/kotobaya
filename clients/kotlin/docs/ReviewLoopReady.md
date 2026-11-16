
# ReviewLoopReady

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **learning** | **kotlin.Int** | Learning/relearning cards whose step has elapsed (&#x60;due_at&#x60; &lt;&#x3D; now). Never capped. |  |
| **review** | **kotlin.Int** | Review cards due before &#x60;day_ends_at&#x60; (overdue included) within the remaining review budget; every such card when &#x60;scheduler.reviews_unlimited&#x60;. At most one per note while siblings are buried.  |  |
| **total** | **kotlin.Int** | &#x60;learning + review&#x60;. |  |



