
# NoteCreate

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **noteTypeId** | **kotlin.String** | A live note type. |  |
| **cardKinds** | **kotlin.collections.Set&lt;kotlin.String&gt;** | Card kinds to create. Default - the note type&#39;s default kinds. |  [optional] |
| **deckId** | **kotlin.String** | Also add the note (or its entry) to this user deck. |  [optional] |
| **entrySeq** | **kotlin.Long** | Dictionary entry; vocab types only. |  [optional] |
| **fields** | **kotlin.collections.Map&lt;kotlin.String, kotlin.String&gt;** | Field values by key. |  [optional] |
| **grammarPointId** | **kotlin.String** | A live grammar point; grammar types only. |  [optional] |
| **id** | **kotlin.String** | Optional client-generated UUIDv7 (idempotent creation). |  [optional] |
| **kanjiLiteral** | **kotlin.String** | One kanji (one code point); kanji types only. |  [optional] |
| **mnemonic** | **kotlin.String** | Mnemonic (Markdown). |  [optional] |
| **origin** | **kotlin.String** | How the note was created. |  [optional] |
| **sourceMediaId** | **kotlin.String** | Soft reference to the media. |  [optional] |
| **sourceSentence** | **kotlin.String** | Sentence the note was mined from. |  [optional] |
| **sourceSentenceId** | **kotlin.String** | Soft reference to the sentence. |  [optional] |
| **tags** | **kotlin.collections.List&lt;kotlin.String&gt;** | Tags. |  [optional] |



