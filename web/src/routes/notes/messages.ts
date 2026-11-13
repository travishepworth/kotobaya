// Notes editor strings added by the rebuild (FEAT-x field boxes); the rest come from lib/notes/messages.
const en = {
	'nx.previewFailed': 'This note could not be rendered',
	'nx.fieldsHint': 'Fields marked “From dictionary” fill themselves. Override one to use your own text on every card of this note.',
	'nx.fromDictionary': 'From dictionary',
	'nx.override': 'Your override',
	'nx.doOverride': 'Override',
	'nx.reset': 'Reset to dictionary',
	'nx.dictWas': 'Dictionary: {value}',
	'nx.emptyField': 'Empty',
	'nx.more': 'Tags, mnemonic and source',
	'nx.deleteTitle': 'Delete this note?'
} as const;

export type NotesViewKey = keyof typeof en;

export function nx(key: NotesViewKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
