// Dictionary page strings. Same contract as ../i18n.ts (English only).
const en = {
	'dict.title': 'Dictionary',
	'dict.search': 'Search the dictionary',
	'dict.searchHelp': 'Kanji, kana, romaji or English.',
	'dict.commonOnly': 'Common words only',
	'dict.searching': 'Searching…',
	'dict.none': 'No entries match.',
	'dict.empty': 'Type something to search.',
	'dict.results': 'Results',
	'dict.more': 'Load more',
	'dict.common': 'common',
	'dict.rank': 'frequency #{n}',
	'dict.retired': 'retired',
	'dict.hasNote': 'in notes',
	'dict.status': 'Status: {status}',
	'dict.selectPrompt': 'Select an entry to see it.',
	'dict.entry': 'Entry',
	'dict.loadingEntry': 'Loading entry…',
	'dict.kanjiForms': 'Kanji forms',
	'dict.readings': 'Readings',
	'dict.senses': 'Senses',
	'dict.pitch': 'Pitch accent',
	'dict.pitchHeiban': 'flat (0)',
	'dict.pitchDrop': 'drop after mora {n}',
	'dict.frequency': 'Frequency',
	'dict.kanji': 'Kanji',
	'dict.examples': 'Examples',
	'dict.examplesMedia': 'From your media',
	'dict.examplesTatoeba': 'Tatoeba',
	'dict.examplesNone': 'No examples.',
	'dict.addNote': 'Add as note',
	'dict.adding': 'Adding…',
	'dict.added': 'Note added.',
	'dict.noVocabType': 'No vocab note type is available.',
	'dict.openNotes': 'Open in Notes',
	'dict.kanjiOpen': 'Show kanji {k}',
	'dict.kanjiPanel': 'Kanji details',
	'dict.kanjiClose': 'Close',
	'dict.kanjiLoading': 'Loading kanji…',
	'dict.kanjiOn': 'On',
	'dict.kanjiKun': 'Kun',
	'dict.kanjiStrokes': 'Strokes: {n}',
	'dict.kanjiJlpt': 'JLPT N{n}',
	'dict.kanjiGrade': 'Grade {n}',
	'dict.kanjiTabFrequent': 'Most frequent',
	'dict.kanjiTabMine': 'In my decks',
	'dict.kanjiDeckFilter': 'Deck',
	'dict.kanjiAllDecks': 'All decks',
	'dict.kanjiNoWords': 'No words.',
	'dict.kanjiDeckCount': '{name} ×{n}'
} as const;

export type DictMessageKey = keyof typeof en;

export function td(key: DictMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
