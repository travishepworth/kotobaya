// Note-type screen strings added by the rebuild; the rest come from lib/note-types/messages.
const en = {
	'nt2.lead': 'The shape of a note: its fields, the cards it makes, and how they look.',
	'nt2.sections': 'Editor sections',
	'nt2.tab.fields': 'Fields',
	'nt2.tab.templates': 'Templates',
	'nt2.tab.style': 'Style',
	'nt2.default': 'Created for new notes',
	'nt2.yours': 'Your note types',
	'nt2.builtins': 'Built in',
	'nt2.emptyTitle': 'No note types',
	'nt2.deleteTitle': 'Delete “{name}”?',
	'nt2.danger': 'Delete note type',
	'nt2.dangerBody': 'Only note types no note uses can be deleted.'
} as const;

export type NoteTypeViewKey = keyof typeof en;

export function nt(key: NoteTypeViewKey, params?: Record<string, string | number>): string {
	let s: string = en[key];
	if (params) for (const [k, v] of Object.entries(params)) s = s.replaceAll(`{${k}}`, String(v));
	return s;
}
