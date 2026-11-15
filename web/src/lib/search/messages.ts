// Merged search strings (ADR-026). Same contract as ../i18n.ts (English only).
const en = {
	'ms.title': 'Search',
	'ms.label': 'Search words, notes and cards',
	'ms.placeholder': 'Word, reading, meaning, or a card query like is:due lapses>3',
	'ms.submit': 'Search',
	'ms.startTitle': 'Search everything in one place',
	'ms.startBody': 'Dictionary words, your notes and your cards. Card-search operators such as deck:Frieren or is:due list cards.',
	'ms.noneTitle': 'Nothing found',
	'ms.noneBody': 'Try the kana or kanji form, or a shorter query.',
	'ms.words': 'Dictionary',
	'ms.notes': 'Your notes',
	'ms.cards': 'Cards',
	'ms.inCards': 'In my cards',
	'ms.status': 'Status: {status}',
	'ms.common': 'common',
	'ms.cardCount': '{n} cards',
	'ms.more': 'More words',
	'ms.invalid': 'This card query is not valid: {detail}',
	'ms.failed': 'The search failed.',
	'ms.openWord': 'Open {word} in the dictionary',
	'ms.openNote': 'Open note {word}'
} as const;

export type MergedSearchKey = keyof typeof en;

export function tms(key: MergedSearchKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
