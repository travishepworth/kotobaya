import type { NoteBaseType, NoteField, NoteType, NoteTypeCreate } from '../api/generated';
import type { NoteType as CoreNoteType, RenderOptions } from '../core/index';

export type FieldKind = NoteField['kind'];

export interface FieldDraft {
	key: string;
	label: string;
	kind: FieldKind;
	/** Existing on the server: the key is immutable and the field cannot be removed. */
	saved: boolean;
}

export interface KindDraft {
	kind: string;
	/** `templates.<kind>.default`: created for new notes. */
	enabled: boolean;
	/** Empty = the core's built-in template. */
	front: string;
	back: string;
}

export interface NoteTypeDraft {
	name: string;
	baseType: NoteBaseType;
	fields: FieldDraft[];
	kinds: KindDraft[];
	css: string;
}

export const KEY_PATTERN = /^[a-z][a-z0-9_]*$/;

export const BASE_TYPES: NoteBaseType[] = [
	'vocab',
	'kanji',
	'phrase',
	'grammar',
	'sentence',
	'custom'
];

export function emptyDraft(): NoteTypeDraft {
	return {
		name: '',
		baseType: 'custom',
		fields: [
			{ key: 'front', label: 'Front', kind: 'text', saved: false },
			{ key: 'back', label: 'Back', kind: 'text', saved: false }
		],
		kinds: [{ kind: 'basic', enabled: true, front: '', back: '' }],
		css: ''
	};
}

export function toDraft(nt: {
	name: string;
	baseType: NoteBaseType;
	fields: NoteField[];
	templates: NoteType['templates'];
	css: string;
}): NoteTypeDraft {
	return {
		name: nt.name,
		baseType: nt.baseType,
		fields: nt.fields.map((f) => ({ key: f.key, label: f.label, kind: f.kind, saved: true })),
		kinds: Object.entries(nt.templates).map(([kind, t]) => ({
			kind,
			enabled: t._default,
			front: t.front ?? '',
			back: t.back ?? ''
		})),
		css: nt.css
	};
}

export function fieldsOf(d: NoteTypeDraft): NoteField[] {
	return d.fields.map((f) => ({ key: f.key, label: f.label.trim() || f.key, kind: f.kind }));
}

export function templatesOf(d: NoteTypeDraft): NoteType['templates'] {
	return Object.fromEntries(
		d.kinds.map((k) => [
			k.kind,
			{ _default: k.enabled, front: k.front.trim() === '' ? null : k.front, back: k.back.trim() === '' ? null : k.back }
		])
	);
}

export function toCreate(d: NoteTypeDraft): NoteTypeCreate {
	return {
		name: d.name.trim(),
		baseType: d.baseType,
		fields: fieldsOf(d),
		templates: templatesOf(d),
		css: d.css
	};
}

export type DraftProblem =
	| { code: 'name' }
	| { code: 'fields' }
	| { code: 'fieldKey'; key: string }
	| { code: 'fieldDup'; key: string }
	| { code: 'kindKey'; key: string }
	| { code: 'noDefault' };

/** Client-side checks the server repeats; keeps obvious mistakes off the network. */
export function validate(d: NoteTypeDraft): DraftProblem | null {
	if (d.name.trim() === '') return { code: 'name' };
	if (d.fields.length === 0) return { code: 'fields' };
	const seen = new Set<string>();
	for (const f of d.fields) {
		if (!KEY_PATTERN.test(f.key)) return { code: 'fieldKey', key: f.key };
		if (seen.has(f.key)) return { code: 'fieldDup', key: f.key };
		seen.add(f.key);
	}
	for (const k of d.kinds) if (!KEY_PATTERN.test(k.kind)) return { code: 'kindKey', key: k.kind };
	if (d.kinds.length === 0 || !d.kinds.some((k) => k.enabled)) return { code: 'noDefault' };
	return null;
}

/** The draft as the core's render model (what the live preview renders). */
export function toCoreNoteType(d: NoteTypeDraft): CoreNoteType {
	return {
		baseType: d.baseType,
		fieldKeys: d.fields.map((f) => f.key),
		templates: d.kinds.map((k) => ({
			kind: k.kind,
			isDefault: k.enabled,
			front: k.front.trim() === '' ? null : k.front,
			back: k.back.trim() === '' ? null : k.back
		})),
		css: d.css
	};
}

const SAMPLE_VALUES: Record<string, string> = {
	expression: '食べる',
	reading: 'たべる',
	meaning: 'to eat',
	sentence: '毎日りんごを食べる。',
	sentence_translation: 'I eat an apple every day.',
	literal: '食',
	keyword: 'eat',
	readings: 'ショク、た.べる',
	pattern: '〜ている',
	notes: 'Sample note.'
};

/** Initial sample values for the preview: known keys get Japanese samples, others their label. */
export function sampleFields(d: NoteTypeDraft): Record<string, string> {
	return Object.fromEntries(d.fields.map((f) => [f.key, SAMPLE_VALUES[f.key] ?? f.label]));
}

export const PREVIEW_MEDIA_URL = '/api/v1/blobs/{sha256}';

export function previewOptions(furigana: boolean, pitch: boolean): RenderOptions {
	return { furigana, pitch, showMnemonic: true, mediaUrlTemplate: PREVIEW_MEDIA_URL };
}
