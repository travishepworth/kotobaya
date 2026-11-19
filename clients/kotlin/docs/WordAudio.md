
# WordAudio

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **blobHash** | **kotlin.String** | The audio blob (&#x60;GET /blobs/{hash}&#x60;), or null when &#x60;source&#x60; is &#x60;tts&#x60; and the pair is not generated yet. |  |
| **source** | [**inline**](#Source) | Where the sound comes from. Clients treat an unknown value as &#x60;tts&#x60; with whatever &#x60;blob_hash&#x60; says. |  |
| **text** | **kotlin.String** | The speech text of the pair (core &#x60;WordAudio.Tts.text&#x60;) when &#x60;source&#x60; is &#x60;tts&#x60;; null when &#x60;note&#x60;. |  |
| **voice** | **kotlin.String** | The effective voice of the pair (setting &#x60;tts.voice&#x60;, else &#x60;\&quot;3\&quot;&#x60;) when &#x60;source&#x60; is &#x60;tts&#x60;; null when &#x60;note&#x60;. |  |


<a id="Source"></a>
## Enum: source
| Name | Value |
| ---- | ----- |
| source | note, tts |



