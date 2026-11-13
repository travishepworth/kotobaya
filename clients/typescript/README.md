# @kotobaya/api-client@1.0.0

A TypeScript SDK client for the localhost API.

## Usage

First, install the SDK from npm.

```bash
npm install @kotobaya/api-client --save
```

Next, try it out.


```ts
import {
  Configuration,
  AuthApi,
} from '@kotobaya/api-client';
import type { GetPrincipalRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new AuthApi(config);

  try {
    const data = await api.getPrincipal();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```


## Documentation

### API Endpoints

All URIs are relative to */api/v1*

| Class | Method | HTTP request | Description
| ----- | ------ | ------------ | -------------
*AuthApi* | [**getPrincipal**](docs/AuthApi.md#getprincipal) | **GET** /auth/session | Who am I - the credential making this request.
*AuthApi* | [**login**](docs/AuthApi.md#loginoperation) | **POST** /auth/login | Password login; sets the session cookie.
*AuthApi* | [**logout**](docs/AuthApi.md#logout) | **POST** /auth/logout | Revoke the current session and clear the cookie.
*BlobsApi* | [**getBlob**](docs/BlobsApi.md#getblob) | **GET** /blobs/{hash} | Download a blob.
*BlobsApi* | [**putBlob**](docs/BlobsApi.md#putblob) | **PUT** /blobs/{hash} | Upload a blob under its SHA-256.
*BlobsApi* | [**queryBlobs**](docs/BlobsApi.md#queryblobs) | **POST** /blobs/query | Ask which of a set of blobs the server already has.
*CardsApi* | [**bulkCards**](docs/CardsApi.md#bulkcards) | **POST** /cards/bulk | Apply one action to many cards (suspend, tag, move, delete, ...).
*CardsApi* | [**bulkScheduleCards**](docs/CardsApi.md#bulkschedulecards) | **POST** /cards/bulk/schedule | Reschedule or reset many cards (manual review-log rows).
*CardsApi* | [**createCard**](docs/CardsApi.md#createcard) | **POST** /cards | Add an optional card kind to an existing note.
*CardsApi* | [**deleteCard**](docs/CardsApi.md#deletecard) | **DELETE** /cards/{card_id} | Delete one card (permanent tombstone); the note and its other cards stay.
*CardsApi* | [**getCard**](docs/CardsApi.md#getcard) | **GET** /cards/{card_id} | One card with its note summary.
*CardsApi* | [**listCardReviews**](docs/CardsApi.md#listcardreviews) | **GET** /cards/{card_id}/reviews | Review history of a card (its whole replay set plus drills and crams).
*CardsApi* | [**listCards**](docs/CardsApi.md#listcards) | **GET** /cards | Find cards with the card search language.
*CardsApi* | [**updateCard**](docs/CardsApi.md#updatecard) | **PATCH** /cards/{card_id} | Suspend, bury, flag or re-preset one card.
*CompatApi* | [**ankiConnect**](docs/CompatApi.md#ankiconnectoperation) | **POST** /ankiconnect | AnkiConnect-compatible subset for Yomitan and other mining tools.
*DecksApi* | [**addDeckItems**](docs/DecksApi.md#adddeckitems) | **POST** /decks/{deck_id}/items | Add several items to a user deck.
*DecksApi* | [**createDeck**](docs/DecksApi.md#createdeck) | **POST** /decks | Create a user or smart deck, or enable a generated list.
*DecksApi* | [**deleteDeck**](docs/DecksApi.md#deletedeck) | **DELETE** /decks/{deck_id} | Delete a user, smart or list deck. Notes and cards are untouched.
*DecksApi* | [**getDeck**](docs/DecksApi.md#getdeck) | **GET** /decks/{deck_id} | One deck.
*DecksApi* | [**getDeckCoverage**](docs/DecksApi.md#getdeckcoverage) | **GET** /decks/{deck_id}/coverage | Coverage of a deck\&#39;s items by your word statuses.
*DecksApi* | [**listDeckItems**](docs/DecksApi.md#listdeckitems) | **GET** /decks/{deck_id}/items | Items of a deck with dictionary summary and word status.
*DecksApi* | [**listDeckLists**](docs/DecksApi.md#listdecklists) | **GET** /deck-lists | Generated lists (JLPT, frequency bands) that can be enabled as &#x60;list&#x60; decks.
*DecksApi* | [**listDecks**](docs/DecksApi.md#listdecks) | **GET** /decks | List decks.
*DecksApi* | [**putDeckItem**](docs/DecksApi.md#putdeckitem) | **PUT** /decks/{deck_id}/items/{item_key} | Add one item to a user deck, or move it.
*DecksApi* | [**removeDeckItem**](docs/DecksApi.md#removedeckitem) | **DELETE** /decks/{deck_id}/items/{item_key} | Remove one item from a user deck (reversible by putting it again).
*DecksApi* | [**updateDeck**](docs/DecksApi.md#updatedeck) | **PATCH** /decks/{deck_id} | Rename, reprioritise, enable/disable, set limits or settings.
*DevicesApi* | [**createPairing**](docs/DevicesApi.md#createpairing) | **POST** /devices/pairings | Create a one-time pairing code (shown as a QR code by the web app).
*DevicesApi* | [**getDevice**](docs/DevicesApi.md#getdevice) | **GET** /devices/{device_id} | One device.
*DevicesApi* | [**getOwnDevice**](docs/DevicesApi.md#getowndevice) | **GET** /devices/self | The device the calling token is bound to.
*DevicesApi* | [**listDevices**](docs/DevicesApi.md#listdevices) | **GET** /devices | List devices, including revoked ones and the built-in server device.
*DevicesApi* | [**pairDevice**](docs/DevicesApi.md#pairdevice) | **POST** /devices/pair | Exchange a pairing code for a long-lived device token.
*DevicesApi* | [**revokeDevice**](docs/DevicesApi.md#revokedevice) | **DELETE** /devices/{device_id} | Revoke a device and all its tokens.
*DevicesApi* | [**updateDevice**](docs/DevicesApi.md#updatedevice) | **PATCH** /devices/{device_id} | Rename a device or change its push topic.
*DevicesApi* | [**updateOwnDevice**](docs/DevicesApi.md#updateowndevice) | **PATCH** /devices/self | Rename the caller\&#39;s device or change its push topic.
*DictionaryApi* | [**getDictEntry**](docs/DictionaryApi.md#getdictentry) | **GET** /dict/entries/{seq} | One JMdict entry with frequency, pitch, kanji and your status.
*DictionaryApi* | [**getDictRelease**](docs/DictionaryApi.md#getdictrelease) | **GET** /dict/release | The current dictionary release and its downloadable device builds.
*DictionaryApi* | [**getKanji**](docs/DictionaryApi.md#getkanji) | **GET** /kanji/{literal} | One kanji from KANJIDIC2 with components and your kanji note, if any.
*DictionaryApi* | [**listDictHeadwords**](docs/DictionaryApi.md#listdictheadwords) | **GET** /dict/headwords | Headword, reading and first-sense glosses for up to 500 entries in one call (C-18).
*DictionaryApi* | [**listEntryExamples**](docs/DictionaryApi.md#listentryexamples) | **GET** /dict/entries/{seq}/examples | Example sentences for an entry, from your media and from Tatoeba.
*DictionaryApi* | [**listKanjiWords**](docs/DictionaryApi.md#listkanjiwords) | **GET** /kanji/{literal}/words | Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17).
*DictionaryApi* | [**refreshDictionary**](docs/DictionaryApi.md#refreshdictionary) | **POST** /dict/refresh | Queue a dictionary refresh (download sources, build and load a new release).
*DictionaryApi* | [**searchDictionary**](docs/DictionaryApi.md#searchdictionary) | **GET** /dict/search | Search JMdict entries by kanji, kana, romaji or English.
*EventsApi* | [**streamEvents**](docs/EventsApi.md#streamevents) | **GET** /events | Server-sent events - job progress, sync activity, new notes, device changes.
*ExportApi* | [**exportAll**](docs/ExportApi.md#exportall) | **GET** /export | Full JSON dump of your data.
*ExportApi* | [**exportAnki**](docs/ExportApi.md#exportanki) | **GET** /export/anki | Export notes and cards as an Anki package (.apkg).
*GrammarApi* | [**createGrammarPoint**](docs/GrammarApi.md#creategrammarpoint) | **POST** /grammar | Create a grammar point.
*GrammarApi* | [**deleteGrammarPoint**](docs/GrammarApi.md#deletegrammarpoint) | **DELETE** /grammar/{grammar_point_id} | Delete a grammar point.
*GrammarApi* | [**getGrammarPoint**](docs/GrammarApi.md#getgrammarpoint) | **GET** /grammar/{grammar_point_id} | One grammar point.
*GrammarApi* | [**listGrammarExamples**](docs/GrammarApi.md#listgrammarexamples) | **GET** /grammar/{grammar_point_id}/examples | Sentences where the grammar point was seen (detected or highlighted by hand).
*GrammarApi* | [**listGrammarPoints**](docs/GrammarApi.md#listgrammarpoints) | **GET** /grammar | List or search the grammar library.
*GrammarApi* | [**putGrammarSpan**](docs/GrammarApi.md#putgrammarspan) | **PUT** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Highlight a span of a sentence as an example of this grammar point (\&quot;I saw something weird\&quot;).
*GrammarApi* | [**removeGrammarSpan**](docs/GrammarApi.md#removegrammarspan) | **DELETE** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Remove a manual span (reversible by putting it again).
*GrammarApi* | [**testGrammarRules**](docs/GrammarApi.md#testgrammarrules) | **POST** /grammar/{grammar_point_id}/rules/test | Run detection rules against your sentences without saving anything.
*GrammarApi* | [**updateGrammarPoint**](docs/GrammarApi.md#updategrammarpoint) | **PATCH** /grammar/{grammar_point_id} | Edit a grammar point.
*JobsApi* | [**cancelJob**](docs/JobsApi.md#canceljob) | **POST** /jobs/{job_id}/cancel | Cancel a queued or running job.
*JobsApi* | [**getJob**](docs/JobsApi.md#getjob) | **GET** /jobs/{job_id} | One job with progress, result or error.
*JobsApi* | [**listJobs**](docs/JobsApi.md#listjobs) | **GET** /jobs | List background jobs (imports, parses, optimizer runs, ...).
*JobsApi* | [**synthesizeSpeech**](docs/JobsApi.md#synthesizespeech) | **POST** /tts | Synthesize Japanese speech (VOICEVOX) into an audio blob.
*MediaApi* | [**clearJpdbVocabResolution**](docs/MediaApi.md#clearjpdbvocabresolution) | **DELETE** /import/jpdb/vocab/{jpdb_vid}/resolution | Clear a correction - the vocabulary item goes back to the review queue.
*MediaApi* | [**deleteMedia**](docs/MediaApi.md#deletemedia) | **DELETE** /media/{media_id} | Delete a media, its deck and its parsed content.
*MediaApi* | [**getMedia**](docs/MediaApi.md#getmedia) | **GET** /media/{media_id} | One media with its segments.
*MediaApi* | [**getMediaCoverage**](docs/MediaApi.md#getmediacoverage) | **GET** /media/{media_id}/coverage | Coverage of a media (or one segment) by your word statuses.
*MediaApi* | [**getSentence**](docs/MediaApi.md#getsentence) | **GET** /sentences/{sentence_id} | One sentence with tokens and grammar spans.
*MediaApi* | [**importJpdbDeck**](docs/MediaApi.md#importjpdbdeck) | **POST** /media/import/jpdb | Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).
*MediaApi* | [**importJpdbHistory**](docs/MediaApi.md#importjpdbhistory) | **POST** /import/jpdb/history | Import jpdb review history and word statuses from a jpdb export file.
*MediaApi* | [**importMedia**](docs/MediaApi.md#importmedia) | **POST** /media/import | Import your own file(s), a URL or pasted text as media (worker job).
*MediaApi* | [**listJpdbVocab**](docs/MediaApi.md#listjpdbvocab) | **GET** /import/jpdb/vocab | The jpdb mapping review queue - jpdb vocabulary that did not map to a JMdict entry.
*MediaApi* | [**listMedia**](docs/MediaApi.md#listmedia) | **GET** /media | List the media library.
*MediaApi* | [**listMediaSentences**](docs/MediaApi.md#listmediasentences) | **GET** /media/{media_id}/sentences | Sentences of a media, optionally only i+1 sentences.
*MediaApi* | [**listSentences**](docs/MediaApi.md#listsentences) | **GET** /sentences | Search sentences across all media.
*MediaApi* | [**putJpdbVocabResolution**](docs/MediaApi.md#putjpdbvocabresolution) | **PUT** /import/jpdb/vocab/{jpdb_vid}/resolution | Map a jpdb vocabulary item to a JMdict entry, or ignore it.
*MediaApi* | [**reparseMedia**](docs/MediaApi.md#reparsemedia) | **POST** /media/{media_id}/reparse | Re-parse a media (after parse overrides or a dictionary refresh).
*MediaApi* | [**updateMedia**](docs/MediaApi.md#updatemedia) | **PATCH** /media/{media_id} | Change a media\&#39;s title, kind or cover.
*MetaApi* | [**getHealth**](docs/MetaApi.md#gethealth) | **GET** /health | Liveness and readiness probe.
*MetaApi* | [**getMeta**](docs/MetaApi.md#getmeta) | **GET** /meta | Server versions, limits and sync head.
*NoteTypesApi* | [**createNoteType**](docs/NoteTypesApi.md#createnotetype) | **POST** /note-types | Create a note type.
*NoteTypesApi* | [**deleteNoteType**](docs/NoteTypesApi.md#deletenotetype) | **DELETE** /note-types/{note_type_id} | Delete one of your own note types.
*NoteTypesApi* | [**getNoteType**](docs/NoteTypesApi.md#getnotetype) | **GET** /note-types/{note_type_id} | One note type.
*NoteTypesApi* | [**listNoteTypes**](docs/NoteTypesApi.md#listnotetypes) | **GET** /note-types | List note types (built-ins and your own).
*NoteTypesApi* | [**updateNoteType**](docs/NoteTypesApi.md#updatenotetype) | **PATCH** /note-types/{note_type_id} | Edit a note type\&#39;s name, fields, templates or CSS.
*NotesApi* | [**createNote**](docs/NotesApi.md#createnote) | **POST** /notes | Create a note and its cards.
*NotesApi* | [**deleteNote**](docs/NotesApi.md#deletenote) | **DELETE** /notes/{note_id} | Delete a note (permanent tombstone; hides all its cards).
*NotesApi* | [**getNote**](docs/NotesApi.md#getnote) | **GET** /notes/{note_id} | One note with its live cards.
*NotesApi* | [**listNotes**](docs/NotesApi.md#listnotes) | **GET** /notes | List or search notes.
*NotesApi* | [**updateNote**](docs/NotesApi.md#updatenote) | **PATCH** /notes/{note_id} | Edit a note\&#39;s fields, tags, mnemonic, source sentence or dictionary match.
*ParsingApi* | [**deleteParseOverride**](docs/ParsingApi.md#deleteparseoverride) | **DELETE** /parse-overrides/{override_id} | Delete a tokenisation correction (permanent).
*ParsingApi* | [**listParseOverrides**](docs/ParsingApi.md#listparseoverrides) | **GET** /parse-overrides | List your tokenisation corrections.
*ParsingApi* | [**parseText**](docs/ParsingApi.md#parsetext) | **POST** /parse | Parse text - tokens, matched entries and their known status.
*ParsingApi* | [**putParseOverride**](docs/ParsingApi.md#putparseoverride) | **PUT** /parse-overrides/{override_id} | Create or replace a tokenisation correction.
*PresetsApi* | [**createPreset**](docs/PresetsApi.md#createpreset) | **POST** /presets | Create a preset.
*PresetsApi* | [**deletePreset**](docs/PresetsApi.md#deletepreset) | **DELETE** /presets/{preset_id} | Delete a preset.
*PresetsApi* | [**getPreset**](docs/PresetsApi.md#getpreset) | **GET** /presets/{preset_id} | One preset.
*PresetsApi* | [**listPresets**](docs/PresetsApi.md#listpresets) | **GET** /presets | List scheduling presets.
*PresetsApi* | [**optimizePreset**](docs/PresetsApi.md#optimizepreset) | **POST** /presets/{preset_id}/optimize | Queue an FSRS optimizer run for this preset.
*PresetsApi* | [**updatePreset**](docs/PresetsApi.md#updatepreset) | **PATCH** /presets/{preset_id} | Change preset options.
*ReviewApi* | [**answerReview**](docs/ReviewApi.md#answerreview) | **POST** /review/answer | Grade a card.
*ReviewApi* | [**createDrillSession**](docs/ReviewApi.md#createdrillsession) | **POST** /drill/session | Build an extra-practice session that does not touch scheduling.
*ReviewApi* | [**getReviewQueue**](docs/ReviewApi.md#getreviewqueue) | **GET** /review/queue | The next cards to review, built by the shared core\&#39;s queue builder.
*ReviewApi* | [**undoReview**](docs/ReviewApi.md#undoreview) | **POST** /review/undo | Undo a review.
*SettingsApi* | [**listSettings**](docs/SettingsApi.md#listsettings) | **GET** /settings | All stored settings.
*SettingsApi* | [**updateSettings**](docs/SettingsApi.md#updatesettings) | **PATCH** /settings | Set one or more settings.
*StatsApi* | [**getStatsCalibration**](docs/StatsApi.md#getstatscalibration) | **GET** /stats/calibration | FSRS calibration - predicted versus actual recall.
*StatsApi* | [**getStatsForecast**](docs/StatsApi.md#getstatsforecast) | **GET** /stats/forecast | Due forecast per study day, plus the next 24 hours by hour.
*StatsApi* | [**getStatsHeatmap**](docs/StatsApi.md#getstatsheatmap) | **GET** /stats/heatmap | Reviews per study day.
*StatsApi* | [**getStatsKnownWords**](docs/StatsApi.md#getstatsknownwords) | **GET** /stats/known-words | Known words over time.
*StatsApi* | [**getStatsRetention**](docs/StatsApi.md#getstatsretention) | **GET** /stats/retention | Retention (share of reviews not rated Again), grouped.
*StatsApi* | [**getStatsSummary**](docs/StatsApi.md#getstatssummary) | **GET** /stats/summary | Today\&#39;s counts for home screens (due, new, done, time).
*SyncApi* | [**syncPull**](docs/SyncApi.md#syncpull) | **GET** /sync/pull | Pull logged operations after a sequence number.
*SyncApi* | [**syncPush**](docs/SyncApi.md#syncpush) | **POST** /sync/push | Push a batch of locally minted operations.
*TokensApi* | [**createToken**](docs/TokensApi.md#createtoken) | **POST** /tokens | Create a personal access token.
*TokensApi* | [**getToken**](docs/TokensApi.md#gettoken) | **GET** /tokens/{token_id} | One credential.
*TokensApi* | [**listTokens**](docs/TokensApi.md#listtokens) | **GET** /tokens | List credentials of every kind (never the token strings or hashes).
*TokensApi* | [**revokeToken**](docs/TokensApi.md#revoketoken) | **DELETE** /tokens/{token_id} | Revoke a credential of any kind.
*WordsApi* | [**clearWordStatus**](docs/WordsApi.md#clearwordstatus) | **DELETE** /words/{seq}/status | Clear the override; the status falls back to the derived one.
*WordsApi* | [**getWordStatus**](docs/WordsApi.md#getwordstatus) | **GET** /words/{seq}/status | Word status of one entry.
*WordsApi* | [**listConfusables**](docs/WordsApi.md#listconfusables) | **GET** /confusables | All confusable pairs (any note type).
*WordsApi* | [**listHotKanji**](docs/WordsApi.md#listhotkanji) | **GET** /kanji/hot | Hot kanji - recent failures rolled up by kanji.
*WordsApi* | [**listHotWords**](docs/WordsApi.md#listhotwords) | **GET** /words/hot | Hot words - recent struggle, not lifetime.
*WordsApi* | [**listLeeches**](docs/WordsApi.md#listleeches) | **GET** /words/leeches | Leech cards (lapses at or above &#x60;scheduler.leech_threshold&#x60;).
*WordsApi* | [**listWordConfusables**](docs/WordsApi.md#listwordconfusables) | **GET** /words/{seq}/confusables | Confusable partners of the word\&#39;s note.
*WordsApi* | [**setConfusable**](docs/WordsApi.md#setconfusable) | **PUT** /confusables/{note_a_id}/{note_b_id}/{reason} | Confirm or reject a suggested pair, or record a pair yourself.
*WordsApi* | [**setWordStatus**](docs/WordsApi.md#setwordstatus) | **PUT** /words/{seq}/status | Set an explicit status override (known, never-forget, blacklisted, ignored).


### Models

- [AnkiConnectRequest](docs/AnkiConnectRequest.md)
- [AnkiConnectResponse](docs/AnkiConnectResponse.md)
- [Blob](docs/Blob.md)
- [BlobQuery](docs/BlobQuery.md)
- [BlobQueryResult](docs/BlobQueryResult.md)
- [Card](docs/Card.md)
- [CardBulkAction](docs/CardBulkAction.md)
- [CardBulkRequest](docs/CardBulkRequest.md)
- [CardBulkResult](docs/CardBulkResult.md)
- [CardBulkScheduleRequest](docs/CardBulkScheduleRequest.md)
- [CardBulkScheduleRequestAction](docs/CardBulkScheduleRequestAction.md)
- [CardBuriedSchema](docs/CardBuriedSchema.md)
- [CardCreate](docs/CardCreate.md)
- [CardCreatedSchema](docs/CardCreatedSchema.md)
- [CardDeletedSchema](docs/CardDeletedSchema.md)
- [CardFlagSetSchema](docs/CardFlagSetSchema.md)
- [CardMergedSchema](docs/CardMergedSchema.md)
- [CardPage](docs/CardPage.md)
- [CardPatch](docs/CardPatch.md)
- [CardPresetSetSchema](docs/CardPresetSetSchema.md)
- [CardState](docs/CardState.md)
- [CardSuspendedSchema](docs/CardSuspendedSchema.md)
- [CardTemplate](docs/CardTemplate.md)
- [CardUnburiedSchema](docs/CardUnburiedSchema.md)
- [CardUnsuspendedSchema](docs/CardUnsuspendedSchema.md)
- [Confusable](docs/Confusable.md)
- [ConfusableConfirmedSchema](docs/ConfusableConfirmedSchema.md)
- [ConfusableList](docs/ConfusableList.md)
- [ConfusablePage](docs/ConfusablePage.md)
- [ConfusableReason](docs/ConfusableReason.md)
- [ConfusableRejectedSchema](docs/ConfusableRejectedSchema.md)
- [ConfusableSet](docs/ConfusableSet.md)
- [ConfusableStatus](docs/ConfusableStatus.md)
- [ConfusableSuggestedSchema](docs/ConfusableSuggestedSchema.md)
- [Coverage](docs/Coverage.md)
- [CoverageCounts](docs/CoverageCounts.md)
- [CoverageCurveInner](docs/CoverageCurveInner.md)
- [CoverageDifficulty](docs/CoverageDifficulty.md)
- [CoverageSegmentsInner](docs/CoverageSegmentsInner.md)
- [CoverageTokens](docs/CoverageTokens.md)
- [Deck](docs/Deck.md)
- [DeckContentPublishedSchema](docs/DeckContentPublishedSchema.md)
- [DeckCreate](docs/DeckCreate.md)
- [DeckCreatedSchema](docs/DeckCreatedSchema.md)
- [DeckDeletedSchema](docs/DeckDeletedSchema.md)
- [DeckItem](docs/DeckItem.md)
- [DeckItemList](docs/DeckItemList.md)
- [DeckItemPage](docs/DeckItemPage.md)
- [DeckItemPut](docs/DeckItemPut.md)
- [DeckItemPutSchema](docs/DeckItemPutSchema.md)
- [DeckItemRemovedSchema](docs/DeckItemRemovedSchema.md)
- [DeckItemsAdd](docs/DeckItemsAdd.md)
- [DeckItemsAddItemsInner](docs/DeckItemsAddItemsInner.md)
- [DeckList](docs/DeckList.md)
- [DeckListCatalogue](docs/DeckListCatalogue.md)
- [DeckListCatalogueEntry](docs/DeckListCatalogueEntry.md)
- [DeckPatch](docs/DeckPatch.md)
- [DeckSettings](docs/DeckSettings.md)
- [DeckType](docs/DeckType.md)
- [DeckUpdatedSchema](docs/DeckUpdatedSchema.md)
- [DeckUpdatedSchemaSet](docs/DeckUpdatedSchemaSet.md)
- [DetectionRule](docs/DetectionRule.md)
- [Device](docs/Device.md)
- [DeviceList](docs/DeviceList.md)
- [DevicePatch](docs/DevicePatch.md)
- [DictEntry](docs/DictEntry.md)
- [DictEntryFreqsInner](docs/DictEntryFreqsInner.md)
- [DictEntryKanjiFormsInner](docs/DictEntryKanjiFormsInner.md)
- [DictEntryPitchInner](docs/DictEntryPitchInner.md)
- [DictEntryReadingsInner](docs/DictEntryReadingsInner.md)
- [DictEntrySensesInner](docs/DictEntrySensesInner.md)
- [DictEntrySensesInnerGlossInner](docs/DictEntrySensesInnerGlossInner.md)
- [DictEntrySummary](docs/DictEntrySummary.md)
- [DictEntrySummaryPage](docs/DictEntrySummaryPage.md)
- [DictHeadword](docs/DictHeadword.md)
- [DictHeadwordList](docs/DictHeadwordList.md)
- [DictRelease](docs/DictRelease.md)
- [DictReleaseArtifactsInner](docs/DictReleaseArtifactsInner.md)
- [DictReleaseFreqListsInner](docs/DictReleaseFreqListsInner.md)
- [DrillSession](docs/DrillSession.md)
- [DrillSessionRequest](docs/DrillSessionRequest.md)
- [EntryExamples](docs/EntryExamples.md)
- [Event](docs/Event.md)
- [EventDevice](docs/EventDevice.md)
- [EventHello](docs/EventHello.md)
- [EventNoteCreated](docs/EventNoteCreated.md)
- [EventSyncChanged](docs/EventSyncChanged.md)
- [EventType](docs/EventType.md)
- [ExampleSentenceSource](docs/ExampleSentenceSource.md)
- [ExportDump](docs/ExportDump.md)
- [FieldError](docs/FieldError.md)
- [FsrsParamsCreatedSchema](docs/FsrsParamsCreatedSchema.md)
- [FsrsParamsDeletedSchema](docs/FsrsParamsDeletedSchema.md)
- [FsrsParamsPublishedSchema](docs/FsrsParamsPublishedSchema.md)
- [FsrsParamsPublishedSchemaOptimizerStats](docs/FsrsParamsPublishedSchemaOptimizerStats.md)
- [FsrsParamsUpdatedSchema](docs/FsrsParamsUpdatedSchema.md)
- [FsrsParamsUpdatedSchemaSet](docs/FsrsParamsUpdatedSchemaSet.md)
- [GrammarCardSentence](docs/GrammarCardSentence.md)
- [GrammarCreatedSchema](docs/GrammarCreatedSchema.md)
- [GrammarCreatedSchemaDetectionRulesInner](docs/GrammarCreatedSchemaDetectionRulesInner.md)
- [GrammarCreatedSchemaLinksInner](docs/GrammarCreatedSchemaLinksInner.md)
- [GrammarDeletedSchema](docs/GrammarDeletedSchema.md)
- [GrammarLink](docs/GrammarLink.md)
- [GrammarPoint](docs/GrammarPoint.md)
- [GrammarPointCreate](docs/GrammarPointCreate.md)
- [GrammarPointPage](docs/GrammarPointPage.md)
- [GrammarPointPatch](docs/GrammarPointPatch.md)
- [GrammarRuleTestRequest](docs/GrammarRuleTestRequest.md)
- [GrammarRuleTestResult](docs/GrammarRuleTestResult.md)
- [GrammarRuleTestResultMatchesInner](docs/GrammarRuleTestResultMatchesInner.md)
- [GrammarRuleTestResultMatchesInnerSpansInner](docs/GrammarRuleTestResultMatchesInnerSpansInner.md)
- [GrammarSpan](docs/GrammarSpan.md)
- [GrammarSpanPut](docs/GrammarSpanPut.md)
- [GrammarSpanPutSchema](docs/GrammarSpanPutSchema.md)
- [GrammarSpanRef](docs/GrammarSpanRef.md)
- [GrammarSpanRemovedSchema](docs/GrammarSpanRemovedSchema.md)
- [GrammarUpdatedSchema](docs/GrammarUpdatedSchema.md)
- [GrammarUpdatedSchemaSet](docs/GrammarUpdatedSchemaSet.md)
- [Health](docs/Health.md)
- [HotKanji](docs/HotKanji.md)
- [HotKanjiList](docs/HotKanjiList.md)
- [HotWord](docs/HotWord.md)
- [HotWordList](docs/HotWordList.md)
- [Job](docs/Job.md)
- [JobKind](docs/JobKind.md)
- [JobPage](docs/JobPage.md)
- [JobPayloadDeckBuild](docs/JobPayloadDeckBuild.md)
- [JobPayloadDictRefresh](docs/JobPayloadDictRefresh.md)
- [JobPayloadFsrsOptimize](docs/JobPayloadFsrsOptimize.md)
- [JobPayloadGrammarDetect](docs/JobPayloadGrammarDetect.md)
- [JobPayloadMediaParse](docs/JobPayloadMediaParse.md)
- [JobResultDeckBuild](docs/JobResultDeckBuild.md)
- [JobResultDictRefresh](docs/JobResultDictRefresh.md)
- [JobResultFsrsOptimize](docs/JobResultFsrsOptimize.md)
- [JobResultGrammarDetect](docs/JobResultGrammarDetect.md)
- [JobResultGrammarDetectFanOut](docs/JobResultGrammarDetectFanOut.md)
- [JobResultGrammarDetectMedia](docs/JobResultGrammarDetectMedia.md)
- [JobResultJpdbHistory](docs/JobResultJpdbHistory.md)
- [JobResultJpdbHistoryKanjiUnmappedInner](docs/JobResultJpdbHistoryKanjiUnmappedInner.md)
- [JobResultJpdbHistoryUnmappedInner](docs/JobResultJpdbHistoryUnmappedInner.md)
- [JobResultMedia](docs/JobResultMedia.md)
- [JobResultMediaUnmappedInner](docs/JobResultMediaUnmappedInner.md)
- [JobResultTts](docs/JobResultTts.md)
- [JobStatus](docs/JobStatus.md)
- [JpdbDeckImportRequest](docs/JpdbDeckImportRequest.md)
- [JpdbHistoryImportRequest](docs/JpdbHistoryImportRequest.md)
- [JpdbVocab](docs/JpdbVocab.md)
- [JpdbVocabMedia](docs/JpdbVocabMedia.md)
- [JpdbVocabPage](docs/JpdbVocabPage.md)
- [JpdbVocabResolution](docs/JpdbVocabResolution.md)
- [JpdbVocabResolutionPut](docs/JpdbVocabResolutionPut.md)
- [JpdbVocabResolutionResult](docs/JpdbVocabResolutionResult.md)
- [Kanji](docs/Kanji.md)
- [KanjiWord](docs/KanjiWord.md)
- [KanjiWordDeck](docs/KanjiWordDeck.md)
- [KanjiWordPage](docs/KanjiWordPage.md)
- [LoginRequest](docs/LoginRequest.md)
- [Media](docs/Media.md)
- [MediaContentPublishedSchema](docs/MediaContentPublishedSchema.md)
- [MediaCreatedSchema](docs/MediaCreatedSchema.md)
- [MediaDeckOptions](docs/MediaDeckOptions.md)
- [MediaDeletedSchema](docs/MediaDeletedSchema.md)
- [MediaImportFile](docs/MediaImportFile.md)
- [MediaImportRequest](docs/MediaImportRequest.md)
- [MediaImportRequestOptions](docs/MediaImportRequestOptions.md)
- [MediaKind](docs/MediaKind.md)
- [MediaPage](docs/MediaPage.md)
- [MediaPatch](docs/MediaPatch.md)
- [MediaSegment](docs/MediaSegment.md)
- [MediaUpdatedSchema](docs/MediaUpdatedSchema.md)
- [MediaUpdatedSchemaSet](docs/MediaUpdatedSchemaSet.md)
- [Meta](docs/Meta.md)
- [MetaDictRelease](docs/MetaDictRelease.md)
- [MetaLimits](docs/MetaLimits.md)
- [Note](docs/Note.md)
- [NoteBaseType](docs/NoteBaseType.md)
- [NoteCreate](docs/NoteCreate.md)
- [NoteCreatedSchema](docs/NoteCreatedSchema.md)
- [NoteDeletedSchema](docs/NoteDeletedSchema.md)
- [NoteField](docs/NoteField.md)
- [NoteMergedSchema](docs/NoteMergedSchema.md)
- [NotePage](docs/NotePage.md)
- [NotePatch](docs/NotePatch.md)
- [NoteSummary](docs/NoteSummary.md)
- [NoteType](docs/NoteType.md)
- [NoteTypeCreate](docs/NoteTypeCreate.md)
- [NoteTypeCreatedSchema](docs/NoteTypeCreatedSchema.md)
- [NoteTypeCreatedSchemaFieldsInner](docs/NoteTypeCreatedSchemaFieldsInner.md)
- [NoteTypeDeletedSchema](docs/NoteTypeDeletedSchema.md)
- [NoteTypeList](docs/NoteTypeList.md)
- [NoteTypePatch](docs/NoteTypePatch.md)
- [NoteTypeUpdatedSchema](docs/NoteTypeUpdatedSchema.md)
- [NoteTypeUpdatedSchemaSet](docs/NoteTypeUpdatedSchemaSet.md)
- [NoteUpdatedSchema](docs/NoteUpdatedSchema.md)
- [NoteUpdatedSchemaSet](docs/NoteUpdatedSchemaSet.md)
- [OpSchema](docs/OpSchema.md)
- [OverrideToken](docs/OverrideToken.md)
- [PairRequest](docs/PairRequest.md)
- [PairResponse](docs/PairResponse.md)
- [Pairing](docs/Pairing.md)
- [ParseOverride](docs/ParseOverride.md)
- [ParseOverrideDeletedSchema](docs/ParseOverrideDeletedSchema.md)
- [ParseOverridePage](docs/ParseOverridePage.md)
- [ParseOverridePut](docs/ParseOverridePut.md)
- [ParseOverridePutSchema](docs/ParseOverridePutSchema.md)
- [ParseOverridePutSchemaTokensInner](docs/ParseOverridePutSchemaTokensInner.md)
- [ParseRequest](docs/ParseRequest.md)
- [ParseResult](docs/ParseResult.md)
- [ParsedSentence](docs/ParsedSentence.md)
- [Preset](docs/Preset.md)
- [PresetCreate](docs/PresetCreate.md)
- [PresetList](docs/PresetList.md)
- [PresetPatch](docs/PresetPatch.md)
- [Principal](docs/Principal.md)
- [Problem](docs/Problem.md)
- [PullResponse](docs/PullResponse.md)
- [PushRequest](docs/PushRequest.md)
- [PushRequestOpsInner](docs/PushRequestOpsInner.md)
- [PushResponse](docs/PushResponse.md)
- [PushResult](docs/PushResult.md)
- [PushResultError](docs/PushResultError.md)
- [QueueItem](docs/QueueItem.md)
- [RetentionGroup](docs/RetentionGroup.md)
- [ReviewAnswerRequest](docs/ReviewAnswerRequest.md)
- [ReviewButton](docs/ReviewButton.md)
- [ReviewLog](docs/ReviewLog.md)
- [ReviewLoggedSchema](docs/ReviewLoggedSchema.md)
- [ReviewPage](docs/ReviewPage.md)
- [ReviewQueue](docs/ReviewQueue.md)
- [ReviewQueueCounts](docs/ReviewQueueCounts.md)
- [ReviewResult](docs/ReviewResult.md)
- [ReviewType](docs/ReviewType.md)
- [ReviewUndoRequest](docs/ReviewUndoRequest.md)
- [ReviewUndoneSchema](docs/ReviewUndoneSchema.md)
- [SchedulePreview](docs/SchedulePreview.md)
- [SchedulePreviewOption](docs/SchedulePreviewOption.md)
- [Scope](docs/Scope.md)
- [Sentence](docs/Sentence.md)
- [SentencePage](docs/SentencePage.md)
- [SentenceToken](docs/SentenceToken.md)
- [Setting](docs/Setting.md)
- [SettingList](docs/SettingList.md)
- [SettingsPatch](docs/SettingsPatch.md)
- [SettingsSetSchema](docs/SettingsSetSchema.md)
- [SpanOrigin](docs/SpanOrigin.md)
- [StatsCalibration](docs/StatsCalibration.md)
- [StatsCalibrationBinsInner](docs/StatsCalibrationBinsInner.md)
- [StatsForecast](docs/StatsForecast.md)
- [StatsForecastDaysInner](docs/StatsForecastDaysInner.md)
- [StatsForecastHoursInner](docs/StatsForecastHoursInner.md)
- [StatsHeatmap](docs/StatsHeatmap.md)
- [StatsHeatmapDaysInner](docs/StatsHeatmapDaysInner.md)
- [StatsKnownWords](docs/StatsKnownWords.md)
- [StatsKnownWordsCurrent](docs/StatsKnownWordsCurrent.md)
- [StatsKnownWordsSeriesInner](docs/StatsKnownWordsSeriesInner.md)
- [StatsRetention](docs/StatsRetention.md)
- [StatsSummary](docs/StatsSummary.md)
- [StatsSummaryDoneToday](docs/StatsSummaryDoneToday.md)
- [StatsSummaryDue](docs/StatsSummaryDue.md)
- [StatsSummaryTotals](docs/StatsSummaryTotals.md)
- [TatoebaSentence](docs/TatoebaSentence.md)
- [Template](docs/Template.md)
- [Token](docs/Token.md)
- [TokenCreate](docs/TokenCreate.md)
- [TokenCreated](docs/TokenCreated.md)
- [TokenKind](docs/TokenKind.md)
- [TokenList](docs/TokenList.md)
- [TtsRequest](docs/TtsRequest.md)
- [WordStatus](docs/WordStatus.md)
- [WordStatusClearedSchema](docs/WordStatusClearedSchema.md)
- [WordStatusSet](docs/WordStatusSet.md)
- [WordStatusSetSchema](docs/WordStatusSetSchema.md)
- [WordStatusValue](docs/WordStatusValue.md)

### Authorization


Authentication schemes defined for the API:
<a id="bearerAuth"></a>
#### bearerAuth


- **Type**: HTTP Bearer Token authentication
<a id="sessionCookie"></a>
#### sessionCookie


- **Type**: API key
- **API key parameter name**: `kotobaya_session`
- **Location**: 

## About

This TypeScript SDK client supports the [Fetch API](https://fetch.spec.whatwg.org/)
and is automatically generated by the
[OpenAPI Generator](https://openapi-generator.tech) project:

- API version: `1.0.0`
- Package version: `1.0.0`
- Generator version: `7.27.0`
- Build package: `org.openapitools.codegen.languages.TypeScriptFetchClientCodegen`

The generated npm module supports the following:

- Environments
  * Node.js
  * Webpack
  * Browserify
- Language levels
  * ES5 - you must have a Promises/A+ library installed
  * ES6
- Module systems
  * CommonJS
  * ES6 module system

For more information, please visit [https://kotobaya.invalid/api/docs](https://kotobaya.invalid/api/docs)

## Development

### Building

To build the TypeScript source code, you need to have Node.js and npm installed.
After cloning the repository, navigate to the project directory and run:

```bash
npm install
npm run build
```

### Publishing

Once you've built the package, you can publish it to npm:

```bash
npm publish
```

## License

[Proprietary (personal project)]()
