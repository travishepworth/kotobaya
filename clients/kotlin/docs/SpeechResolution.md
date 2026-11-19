
# SpeechResolution

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **blobHash** | **kotlin.String** | The audio blob when &#x60;status&#x60; is &#x60;ready&#x60;, else null. |  |
| **durationMs** | **kotlin.Int** | Length of the audio when &#x60;status&#x60; is &#x60;ready&#x60; and &#x60;source&#x60; is &#x60;tts&#x60;, else null. |  |
| **retryAfterMs** | **kotlin.Int** | When to ask again - &#x60;pending&#x60; 1000 (60000 when held back by the backlog rule); &#x60;failed&#x60; the time left of the 600 s back-off; null for &#x60;ready&#x60; and &#x60;none&#x60;. |  |
| **source** | [**inline**](#Source) | &#x60;tts&#x60; for a &#x60;text&#x60; item and for a note without its own audio; &#x60;note&#x60; for a note whose &#x60;audio&#x60; field holds a sound; null when &#x60;status&#x60; is &#x60;none&#x60;. |  |
| **status** | [**inline**](#Status) | See &#x60;resolveSpeech&#x60;. Clients treat an unknown value as &#x60;failed&#x60;. |  |
| **text** | **kotlin.String** | The text of the pair (the item&#39;s &#x60;text&#x60;, or the note&#39;s speech text); null when &#x60;source&#x60; is not &#x60;tts&#x60;. |  |
| **voice** | **kotlin.String** | The voice of the pair after defaulting; null when &#x60;source&#x60; is not &#x60;tts&#x60;. |  |


<a id="Source"></a>
## Enum: source
| Name | Value |
| ---- | ----- |
| source | note, tts,  |


<a id="Status"></a>
## Enum: status
| Name | Value |
| ---- | ----- |
| status | ready, pending, failed, none |



