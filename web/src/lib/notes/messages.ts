// Notes page strings. Same contract as ../i18n.ts (English only).
const en = {
	'notes.title': 'Notes',
	'notes.search': 'Search',
	'notes.searchHelp': 'Substring of any field, the mnemonic or the source sentence. Enter to search.',
	'notes.type': 'Type',
	'notes.typeAny': 'Any',
	'notes.tag': 'Tag',
	'notes.go': 'Search',
	'notes.loading': 'Loading…',
	'notes.none': 'No notes match.',
	'notes.loadedCount': '{n} notes loaded',
	'notes.moreAvailable': 'more available (scroll down)',
	'notes.table': 'Notes',
	'notes.col.note': 'Note',
	'notes.col.type': 'Type',
	'notes.col.cards': 'Cards',
	'notes.col.tags': 'Tags',
	'notes.col.created': 'Created',
	'notes.selectPrompt': 'Select a note to edit it.',
	'notes.edit': 'Edit note',
	'notes.fields': 'Fields',
	'notes.refFieldHint': 'Leave empty to render from the dictionary.',
	'notes.tags': 'Tags (space separated)',
	'notes.mnemonic': 'Mnemonic',
	'notes.sourceSentence': 'Source sentence',
	'notes.save': 'Save',
	'notes.saving': 'Saving…',
	'notes.saved': 'Note saved.',
	'notes.nothingChanged': 'Nothing changed.',
	'notes.badTag': 'Tags cannot be "leech" or longer than 64 characters.',
	'notes.delete': 'Delete note',
	'notes.deleteConfirm': 'Delete this note and all its cards? This cannot be undone.',
	'notes.deleteYes': 'Delete',
	'notes.deleteNo': 'Cancel',
	'notes.deleted': 'Note deleted.',
	'notes.preview': 'Preview',
	'notes.previewFront': 'Front',
	'notes.previewBack': 'Back',
	'notes.previewFailed': 'This note could not be rendered: {detail}',
	'notes.previewMissingType': 'The note type of this note is missing.',
	'notes.openDictionary': 'Entry {seq}'
} as const;

export type NotesMessageKey = keyof typeof en;

export function tn(key: NotesMessageKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
