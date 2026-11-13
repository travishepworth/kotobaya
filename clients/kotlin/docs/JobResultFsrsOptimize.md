
# JobResultFsrsOptimize

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **logLoss** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | Log loss of the new weights on the held-out reviews. null when no weights were evaluated (&#x60;reason&#x60; &#x60;too_few_reviews&#x60;, &#x60;out_of_bounds&#x60;, or &#x60;preset_deleted&#x60; found before training). |  |
| **presetId** | **kotlin.String** | The preset. |  |
| **published** | **kotlin.Boolean** | true iff this run minted an &#x60;fsrs_params.published&#x60; op. false when there were too few reviews or the new weights were not better (see &#x60;reason&#x60;); the preset is then unchanged. |  |
| **reviewCount** | **kotlin.Int** | Reviews used (training and held-out together). |  |
| **rmseBins** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | RMSE (bins) of the new weights on the held-out reviews; null exactly when &#x60;log_loss&#x60; is. |  |
| **weightsVersion** | **kotlin.Int** | The preset&#39;s &#x60;weights_version&#x60; after the run (when not published, the version the run read). |  |
| **holdoutReviewCount** | **kotlin.Int** | How many of the &#x60;review_count&#x60; reviews formed the held-out set; 0 when there was none. (C-16) |  [optional] |
| **logLossBefore** | [**java.math.BigDecimal**](java.math.BigDecimal.md) | Log loss, on the same held-out reviews, of the weights the preset had when the run started (the built-in defaults for a never-optimized preset). &#x60;published&#x60; is true only if &#x60;log_loss&#x60; &lt; &#x60;log_loss_before&#x60;. null when it was not computed. (C-16) |  [optional] |
| **reason** | [**inline**](#Reason) | Why nothing was published; null iff &#x60;published&#x60; is true. &#x60;too_few_reviews&#x60; &#x3D; below the optimizer&#39;s minimum; &#x60;not_improved&#x60; &#x3D; held-out log loss not strictly lower than before; &#x60;out_of_bounds&#x60; &#x3D; the optimizer produced a weight outside the FSRS-6 bounds; &#x60;preset_deleted&#x60; &#x3D; the preset was deleted before publication. (C-16) |  [optional] |


<a id="Reason"></a>
## Enum: reason
| Name | Value |
| ---- | ----- |
| reason | too_few_reviews, not_improved, out_of_bounds, preset_deleted,  |



