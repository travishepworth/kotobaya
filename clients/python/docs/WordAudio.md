# WordAudio

ADR-047. The word audio of a vocab note (`QueueItem.word_audio`). Fields added later will be optional, so clients must ignore unknown members.  | `source` | `blob_hash` | `text`, `voice` | Meaning | |---|---|---|---| | `note` | hash, never null | null, null | The first `[sound:<sha256>]` of the note's `audio` field. The note's own audio always wins. | | `tts` | hash | the pair | Generated speech of the pair, known to this replica. | | `tts` | null | the pair | Not generated yet (or, on the phone, not resolved yet): no speaker button; the client may call `resolveSpeech` with the pair. |  A non-null `blob_hash` says the hash is known, not that the bytes are on this device: offline on the phone an uncached blob answers `404` and the client stays silent. The second and third examples are a deck-materialized note with empty fields (食べる) before and after generation; the first is a note with its own `[sound:]`. 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**blob_hash** | **str** | The audio blob (&#x60;GET /blobs/{hash}&#x60;), or null when &#x60;source&#x60; is &#x60;tts&#x60; and the pair is not generated yet. | 
**source** | **str** | Where the sound comes from. Clients treat an unknown value as &#x60;tts&#x60; with whatever &#x60;blob_hash&#x60; says. | 
**text** | **str** | The speech text of the pair (core &#x60;WordAudio.Tts.text&#x60;) when &#x60;source&#x60; is &#x60;tts&#x60;; null when &#x60;note&#x60;. | 
**voice** | **str** | The effective voice of the pair (setting &#x60;tts.voice&#x60;, else &#x60;\&quot;3\&quot;&#x60;) when &#x60;source&#x60; is &#x60;tts&#x60;; null when &#x60;note&#x60;. | 

## Example

```python
from kotobaya_client.models.word_audio import WordAudio

# TODO update the JSON string below
json = "{}"
# create an instance of WordAudio from a JSON string
word_audio_instance = WordAudio.from_json(json)
# print the JSON string representation of the object
print(WordAudio.to_json())

# convert the object into a dict
word_audio_dict = word_audio_instance.to_dict()
# create an instance of WordAudio from a dict
word_audio_from_dict = WordAudio.from_dict(word_audio_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


