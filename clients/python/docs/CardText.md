# CardText

ARCH-CARD-TEXT. Plain-text render of one card. No HTML, entities or markup; lines are separated by `\\n` (U+000A) and no line is empty or has leading/trailing spaces. Normative rules are the KDoc of `kotobaya.contracts.render.CardTextRenderer` (core); summary:  **Text of a field**: the value resolved exactly as for the HTML render (a non-blank note field wins, else the dictionary fill for `expression` / `reading` / `meaning`, else for kanji notes the kanji fill for `literal` / `keyword` / `readings`), furigana off, tags removed, entities decoded, audio and images dropped; `<br>`, `<hr>` and `div p ul ol li` boundaries are line breaks. Bracket furigana `漢字[かんじ]`: `[kanji]` mode keeps the base, `[kana]` mode the reading; other fields keep the brackets as typed.  **Per kind** (built-in templates; front → back): | base / kind | front | back | |---|---|---| | vocab, phrase / meaning; vocab / reading | expression[kanji] | reading[kana], meaning | | vocab, phrase / listening | (empty) | expression[kanji], reading[kana], meaning | | vocab, phrase / production | meaning | expression[kanji], reading[kana] | | vocab / pitch | expression[kanji] | pitch line (`たべꜜもの`; several patterns joined by ` / `), else reading[kana] | | kanji / keyword | literal | keyword, readings | | kanji / writing | keyword | literal, readings | | kanji / components | literal | notes | | grammar / cloze | sentence with the span as `＿＿＿`, translation | sentence with the span in `【】`, pattern, meaning, translation | | grammar / spot | sentence | sentence with the span in `【】`, pattern, meaning, translation | | grammar / recognition | pattern | meaning | | grammar / production | meaning | pattern | | sentence / comprehension | sentence[kanji] | reading[kana], translation | | sentence / listening | (empty) | sentence[kanji], reading[kana], translation | | custom / basic | front | back |  Each cell is one or more lines; empty values contribute none. A back line equal to a front line or to an earlier back line is omitted (a kana-only word shows no reading line). The grammar sentence and translation are `QueueItem.grammar_sentence`; when it is null, front = pattern and back = meaning. A kind with a custom template (or not in the table) is the text of the HTML render: front = its front, back = its back minus lines already on the front. 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**back** | **str** | The answer (what the back adds to the front). Clients show it only once revealed; it is always sent. May be empty. | 
**front** | **str** | The question. Empty iff it has no text (audio-only &#x60;listening&#x60; fronts, or an empty note); clients then show their own placeholder (e.g. \&quot;listening card\&quot;). | 
**reading** | **str** | The kana reading (&#x60;reading[kana]&#x60; of vocab, phrase and sentence notes) or the readings of a kanji note (&#x60;readings&#x60;); null when empty and for grammar and custom notes. Answer-side information, already contained in &#x60;back&#x60; where the kind shows it; for layouts that place it separately. | 
**sentence** | **str** | The note&#39;s &#x60;sentence&#x60; field (&#x60;[kanji]&#x60; mode) as front-safe context, for every base type except sentence; null when empty. Not part of &#x60;front&#x60; or &#x60;back&#x60; under built-in templates (a custom template may include it). Not the grammar cloze sentence. | 

## Example

```python
from kotobaya_client.models.card_text import CardText

# TODO update the JSON string below
json = "{}"
# create an instance of CardText from a JSON string
card_text_instance = CardText.from_json(json)
# print the JSON string representation of the object
print(CardText.to_json())

# convert the object into a dict
card_text_dict = card_text_instance.to_dict()
# create an instance of CardText from a dict
card_text_from_dict = CardText.from_dict(card_text_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


