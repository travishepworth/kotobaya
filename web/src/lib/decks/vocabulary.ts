import { PresetsApi } from '../api/generated';
import { apiConfig } from '../api/client';
import type { SearchVocabulary } from '../core/index';
import { noteTypesApi } from '../note-types/api';
import { decksApi } from './api';

const presetsApi = new PresetsApi(apiConfig);

/** Names the search completer offers (deck:, note:, preset:, kind:, field keys). Tags are not offered here. */
export async function loadVocabulary(): Promise<SearchVocabulary> {
	const [decks, noteTypes, presets] = await Promise.all([
		decksApi.listDecks({}),
		noteTypesApi.listNoteTypes({}),
		presetsApi.listPresets({})
	]);
	const kinds = new Set<string>();
	const fieldKeys = new Set<string>();
	for (const nt of noteTypes.items) {
		for (const k of Object.keys(nt.templates)) kinds.add(k);
		for (const f of nt.fields) fieldKeys.add(f.key);
	}
	return {
		deckNames: decks.items.map((d) => d.name),
		tags: [],
		cardKinds: [...kinds],
		noteTypeNames: noteTypes.items.map((n) => n.name),
		presetNames: presets.items.map((p) => p.name),
		fieldKeys: [...fieldKeys]
	};
}

export const EMPTY_VOCABULARY: SearchVocabulary = {
	deckNames: [],
	tags: [],
	cardKinds: [],
	noteTypeNames: [],
	presetNames: [],
	fieldKeys: []
};
