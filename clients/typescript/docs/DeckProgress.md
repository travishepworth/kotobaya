
# DeckProgress

ADR-041. Progress of one deck; the wire form of the core\'s `DeckProgressCount`. **Deck words**: the deck\'s live items de-duplicated - an item `e:<seq>` is the entry; an item `n:<id>` is the entry of its live note (after merge resolution) when it has one, else the note itself; an item whose note is deleted is no word. **Counted** (`total`): every deck word except entries whose word status is `blacklisted` or `ignored`. Words without a note and words missing from the dictionary count. **Learned**: a counted word whose word status is `known` or `never_forget`, or whose note has at least one live card in state `review` that is not retired (ADR-035: vocab `reading` cards while `cards.reading_enabled` is off). Cards in `new`, `learning` or `relearning` do not count (a lapsed word is not learned until it graduates again); suspension and interval are irrelevant. Invariants: `0 <= learned <= total`; `percent = floor(100 * learned / total)`, 0 when `total` is 0 (so 100 only when every word is learned). 

## Properties

Name | Type
------------ | -------------
`learned` | number
`percent` | number
`total` | number

## Example

```typescript
import type { DeckProgress } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "learned": null,
  "percent": null,
  "total": null,
} satisfies DeckProgress

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckProgress
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


