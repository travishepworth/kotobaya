
# DictRelease

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **artifacts** | [**kotlin.collections.List&lt;DictReleaseArtifactsInner&gt;**](DictReleaseArtifactsInner.md) | Downloadable device builds of this release. |  |
| **builtAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Build time. |  |
| **freqLists** | [**kotlin.collections.List&lt;DictReleaseFreqListsInner&gt;**](DictReleaseFreqListsInner.md) | Loaded frequency lists by priority (0 &#x3D; primary). |  |
| **id** | **kotlin.Int** | Release number; higher &#x3D; newer. |  |
| **loadedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When it was loaded into Postgres. |  |
| **sources** | [**kotlin.collections.Map&lt;kotlin.String, kotlin.Any&gt;**](kotlin.Any.md) | Provenance per source for the attribution page: &#x60;{\&quot;jmdict\&quot;: {\&quot;version\&quot;, \&quot;license\&quot;, \&quot;url\&quot;}, ...}&#x60;. |  |
| **version** | **kotlin.String** | Human-readable version. |  |



