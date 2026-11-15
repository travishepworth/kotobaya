
# CardText

ARCH-CARD-TEXT. Plain-text render of one card. No HTML, entities or markup; lines are separated by `\\n` (U+000A) and no line is empty or has leading/trailing spaces. Normative rules are the KDoc of `kotobaya.contracts.render.CardTextRenderer` (core); summary:  **Text of a field**: the value resolved exactly as for the HTML render (a non-blank note field wins, else the dictionary fill for `expression` / `reading` / `meaning`, else for kanji notes the kanji fill for `literal` / `keyword` / `readings`), furigana off, tags removed, entities decoded, audio and images dropped; `<br>`, `<hr>` and `div p ul ol li` boundaries are line breaks. Bracket furigana `漢字[かんじ]`: `[kanji]` mode keeps the base, `[kana]` mode the reading; every other field (HTML mode) keeps the brackets as typed, exactly as the HTML render\'s `{{key}}` does.  **Per kind** (built-in templates; front → back): | base / kind | front | back | |---|---|---| | vocab, phrase / meaning; vocab / reading | expression[kanji] | reading[kana], meaning | | vocab, phrase / listening | (empty) | expression[kanji], reading[kana], meaning | | vocab, phrase / production | meaning | expression[kanji], reading[kana] | | vocab / pitch | expression[kanji], reading[kana] | pitch line (`たべꜜもの`; several patterns joined by ` / `); empty without pitch data | | kanji / keyword | literal | keyword, readings | | kanji / writing | keyword | literal, readings | | kanji / components | literal | notes | | grammar / cloze | sentence with the span as `＿＿＿`, translation | sentence with the span in `【】`, pattern, meaning | | grammar / spot | sentence | sentence with the span in `【】`, pattern, meaning, translation | | grammar / recognition | pattern | meaning | | grammar / production | meaning | pattern | | sentence / comprehension | sentence[kanji] | reading[kana], translation | | sentence / listening | (empty) | sentence[kanji], reading[kana], translation | | custom / basic | front | back |  Each cell is one or more lines, output as listed; empty values contribute none. The pitch line is built from the entry\'s pitch row for `headword_reading` only (none: empty back). The grammar sentence and translation are `QueueItem.grammar_sentence`; when it is null, front = pattern and back = meaning. A kind with a custom template (or not in the table) is the text of the HTML render: front = its front, back = its back minus lines equal to a front line or an earlier back line (removes what `{{FrontSide}}` repeated).  Fields added to `CardText` later will be optional, so clients must ignore unknown members. 

## Properties

Name | Type
------------ | -------------
`back` | string
`front` | string
`reading` | string
`sentence` | string

## Example

```typescript
import type { CardText } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "back": null,
  "front": null,
  "reading": null,
  "sentence": null,
} satisfies CardText

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CardText
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


