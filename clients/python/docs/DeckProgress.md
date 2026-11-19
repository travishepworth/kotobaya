# DeckProgress

ADR-041. Progress of one deck; the wire form of the core's `DeckProgressCount`. **Deck words**: the deck's live items de-duplicated - an item `e:<seq>` is the entry; an item `n:<id>` is the entry of its live note (after merge resolution) when it has one, else the note itself; an item whose note is deleted is no word. **Counted** (`total`): every deck word except entries whose word status is `blacklisted` or `ignored`. Words without a note and words missing from the dictionary count. **Learned**: a counted word whose word status is `known` or `never_forget`, or whose note has at least one live card in state `review` that is not retired (ADR-035: vocab `reading` cards while `cards.reading_enabled` is off). Cards in `new`, `learning` or `relearning` do not count (a lapsed word is not learned until it graduates again); suspension and interval are irrelevant. Invariants: `0 <= learned <= total`; `percent = floor(100 * learned / total)`, 0 when `total` is 0 (so 100 only when every word is learned). 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**learned** | **int** | Deck words that are learned. | 
**percent** | **int** | &#x60;floor(100 * learned / total)&#x60;; 0 when &#x60;total&#x60; is 0. Clients display it as is and do not recompute it. | 
**total** | **int** | Deck words that count. | 

## Example

```python
from kotobaya_client.models.deck_progress import DeckProgress

# TODO update the JSON string below
json = "{}"
# create an instance of DeckProgress from a JSON string
deck_progress_instance = DeckProgress.from_json(json)
# print the JSON string representation of the object
print(DeckProgress.to_json())

# convert the object into a dict
deck_progress_dict = deck_progress_instance.to_dict()
# create an instance of DeckProgress from a dict
deck_progress_from_dict = DeckProgress.from_dict(deck_progress_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


