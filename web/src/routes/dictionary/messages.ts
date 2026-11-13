// Dictionary screen strings added by the rebuild (the rest come from lib/dictionary/messages).
const en = {
	'dx.searchLabel': 'Search words, kanji, readings or meanings',
	'dx.placeholder': '取る, toru, take…',
	'dx.commonOnly': 'Common only',
	'dx.try': 'Try',
	'dx.startTitle': 'Look anything up',
	'dx.startBody': 'Kanji, kana, romaji or English all work. Press / to jump to the search box.',
	'dx.noneTitle': 'No words match “{q}”',
	'dx.noneBody': 'Check the spelling, try the reading in kana or romaji, or search by an English meaning.',
	'dx.pickTitle': 'Pick a word',
	'dx.pickBody': 'Its readings, pitch accent, senses, kanji and examples appear here.',
	'dx.results': 'Search results',
	'dx.backToResults': 'Results',
	'dx.inNotes': 'In your notes',
	'dx.common': 'Common',
	'dx.freq': '#{n}',
	'dx.freqLong': 'Frequency rank {n}',
	'dx.retired': 'Retired entry',
	'dx.status.known': 'Known',
	'dx.status.learning': 'Learning',
	'dx.status.new': 'New',
	'dx.status.ignored': 'Ignored',
	'dx.status.never_forget': 'Never forget',
	'dx.status.blacklisted': 'Blacklisted',
	'dx.tab.senses': 'Senses',
	'dx.tab.kanji': 'Kanji',
	'dx.tab.examples': 'Examples',
	'dx.tabs': 'Entry sections',
	'dx.otherForms': 'Other forms',
	'dx.readings': 'Readings',
	'dx.addNote': 'Add to my notes',
	'dx.openNote': 'Open note',
	'dx.kanjiHint': 'Choose a kanji to see its meanings, readings and the words that use it.',
	'dx.kanjiTiles': 'Kanji in this word',
	'dx.kanjiMeanings': 'Meanings',
	'dx.kanjiOn': 'On’yomi',
	'dx.kanjiKun': 'Kun’yomi',
	'dx.kanjiNanori': 'Name readings',
	'dx.kanjiStrokes': '{n} strokes',
	'dx.kanjiWords': 'Words using {k}',
	'dx.kanjiInDecks': 'In {n} decks',
	'dx.kanjiNoWords': 'No words to show.',
	'dx.kanjiNoMine': 'None of your decks use this kanji yet.',
	'dx.examplesMedia': 'From your reading',
	'dx.examplesTatoeba': 'Tatoeba',
	'dx.examplesNone': 'No example sentences for this word yet.',
	'dx.capture': 'Mark grammar',
	'dx.loadingEntry': 'Loading entry…'
} as const;

export type DictViewKey = keyof typeof en;

export function dx(key: DictViewKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
