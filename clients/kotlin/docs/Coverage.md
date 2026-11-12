
# Coverage

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **computedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When the numbers were computed. |  |
| **curve** | [**kotlin.collections.List&lt;CoverageCurveInner&gt;**](CoverageCurveInner.md) | \&quot;Learn the top N unknown words to reach X%\&quot;: points for N &#x3D; 0, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000 (those below the number of unknown entries) and a final point for all of them. Unknown entries are taken in descending occurrence count, ties by &#x60;seq&#x60;. |  |
| **deckId** | **kotlin.String** | The deck measured (equals the media id for media). |  |
| **difficulty** | [**CoverageDifficulty**](CoverageDifficulty.md) |  |  |
| **hide** | [**inline**](#kotlin.collections.List&lt;Hide&gt;) | Classes that were left out. |  |
| **ignoredCount** | **kotlin.Int** | Distinct entries with status &#x60;blacklisted&#x60; or &#x60;ignored&#x60; (reported whether hidden or not). |  |
| **segmentId** | **kotlin.String** | The segment measured, or null for the whole deck or media. |  |
| **segments** | [**kotlin.collections.List&lt;CoverageSegmentsInner&gt;**](CoverageSegmentsInner.md) | Per-segment summary in &#x60;ordinal&#x60; order (empty for non-media decks). |  |
| **tokens** | [**CoverageTokens**](CoverageTokens.md) |  |  |
| **unique** | [**CoverageCounts**](CoverageCounts.md) | Distinct entries. |  |


<a id="kotlin.collections.List<Hide>"></a>
## Enum: hide
| Name | Value |
| ---- | ----- |
| hide | names, particles, auxiliary, ignored, unmapped |



