<script lang="ts">
	// Note editor with the WYSIWYG card (FEAT-x): the rendered card on top; under it every field as a box that says
	// where its value comes from (dictionary / your override / your text). "Override" turns a dictionary value into
	// your own text, "Reset to dictionary" clears it again; overridden regions are marked in the card. Clicking a
	// field region in the card (C-19 regions) opens that field.
	import Button from '../../lib/ui/Button.svelte';
	import Dialog from '../../lib/ui/Dialog.svelte';
	import TextArea from '../../lib/ui/TextArea.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { ut } from '../../lib/ui/messages';
	import { tn } from '../../lib/notes/messages';
	import { NoteEditorState, type DictEntry, type Note, type NoteType } from '../../lib/state/notes.svelte';
	import PreviewPane from '../cards/PreviewPane.svelte';
	import { nx } from './messages';

	interface Props {
		note: Note;
		noteType: NoteType | undefined;
		onentry: (seq: number, entry: DictEntry) => void;
		onsaved: (n: Note) => void;
		ondeleted: (id: string) => void;
		/** Field to open once the note has loaded (deep link from a click in the /cards preview). */
		initialField?: string | null;
	}
	let { note, noteType, onentry, onsaved, ondeleted, initialField = null }: Props = $props();

	const s = new NoteEditorState(
		() => note,
		() => noteType,
		() => ({ onentry, onsaved, ondeleted })
	);

	let editing = $state<string | null>(null);
	$effect(() => {
		void note.id;
		editing = null;
	});

	type Source = 'dictionary' | 'override' | 'note' | 'empty';
	function sourceOf(key: string): Source {
		const own = s.fields[key] ?? '';
		const dict = s.dictValues[key] ?? '';
		if (own !== '' && dict !== '') return 'override';
		if (own === '' && dict !== '') return 'dictionary';
		return own !== '' ? 'note' : 'empty';
	}
	const overridden = $derived(s.fieldKeys.filter((k) => sourceOf(k) === 'override'));
	const dirty = $derived(
		s.fieldKeys.some((k) => (s.fields[k] ?? '') !== (note.fields[k] ?? '')) ||
			s.mnemonic !== note.mnemonic ||
			s.sourceSentence !== note.sourceSentence ||
			s.tagsText.trim() !== note.tags.join(' ')
	);

	function plain(html: string): string {
		return html.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
	}
	function override(key: string): void {
		if ((s.fields[key] ?? '') === '') s.fields[key] = s.dictValues[key] ?? '';
		editing = key;
	}
	function reset(key: string): void {
		s.fields[key] = '';
		if (editing === key) editing = null;
	}
	function openField(key: string): void {
		if (!s.fieldKeys.includes(key)) return;
		if (sourceOf(key) === 'dictionary') override(key);
		else editing = key;
		requestAnimationFrame(() => document.getElementById(`field-${key}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }));
	}

	// Open the deep-linked field once, after the dictionary values arrived (so it opens as an override).
	let pendingField = $state<string | null>(null);
	$effect.pre(() => {
		void note.id;
		pendingField = initialField;
	});
	$effect(() => {
		if (pendingField === null || s.ref.loading || s.fieldKeys.length === 0) return;
		const key = pendingField;
		pendingField = null;
		openField(key);
	});

	function save(e: Event): void {
		e.preventDefault();
		if (!s.tagsValid) return;
		void s.save();
	}
	const notice = $derived(s.ref.loading ? tn('notes.dictLoading') : s.ref.failed ? tn('notes.dictFailed') : null);
</script>

<form class="editor" onsubmit={save}>
	<h2 class="title">{tn('notes.edit')}</h2>

	<PreviewPane
		rendered={s.rendered}
		bind:side={s.side}
		kinds={s.kinds}
		kind={s.activeKind}
		kindLabel={(k) => s.kindLabel(k)}
		onkind={(k) => (s.kind = k)}
		errorTitle={nx('nx.previewFailed')}
		{notice}
		onfield={openField}
		{overridden}
		activeField={editing}
	/>

	<section class="fields" aria-labelledby="fields-h">
		<div class="section-head">
			<h3 id="fields-h">{tn('notes.fields')}</h3>
			{#if s.referenced}<p class="hint">{nx('nx.fieldsHint')}</p>{/if}
		</div>
		{#each s.fieldKeys as key (key)}
			{@const src = sourceOf(key)}
			{@const value = src === 'dictionary' ? (s.dictValues[key] ?? '') : (s.fields[key] ?? '')}
			<div class="field {src}" class:open={editing === key} id="field-{key}">
				<div class="field-head">
					<span class="label">{s.labelOf(key)}</span>
					{#if src === 'dictionary'}
						<span class="badge dict">{nx('nx.fromDictionary')}</span>
					{:else if src === 'override'}
						<span class="badge over">{nx('nx.override')}</span>
					{/if}
					<span class="field-actions">
						{#if src === 'dictionary'}
							<Button size="sm" variant="quiet" icon="edit" onclick={() => override(key)}>{nx('nx.doOverride')}</Button>
						{:else if editing !== key}
							<Button size="sm" variant="quiet" icon="edit" onclick={() => (editing = key)}>{ut('app.edit')}</Button>
						{/if}
						{#if src === 'override'}
							<Button size="sm" variant="quiet" icon="undo" onclick={() => reset(key)}>{nx('nx.reset')}</Button>
						{/if}
					</span>
				</div>
				{#if editing === key}
					<TextArea label={s.labelOf(key)} hideLabel value={s.fields[key] ?? ''} oninput={(e) => (s.fields[key] = (e.currentTarget as HTMLTextAreaElement).value)} rows={2} lang="ja" />
					{#if src === 'override'}<p class="hint">{nx('nx.dictWas', { value: plain(s.dictValues[key] ?? '') })}</p>{/if}
				{:else if value !== ''}
					<p class="value" lang="ja">{plain(value)}</p>
				{:else}
					<p class="value empty">{nx('nx.emptyField')}</p>
				{/if}
			</div>
		{/each}
	</section>

	<section class="more" aria-label={nx('nx.more')}>
		<TextField
			label={tn('notes.tags')}
			bind:value={s.tagsText}
			error={s.tagsValid ? undefined : tn('notes.badTag')}
		/>
		<TextArea label={tn('notes.mnemonic')} bind:value={s.mnemonic} rows={3} />
		<TextArea label={tn('notes.sourceSentence')} bind:value={s.sourceSentence} rows={2} lang="ja" />
	</section>

	<div class="actions">
		<Button variant="danger" icon="trash" onclick={() => (s.confirming = true)}>{tn('notes.delete')}</Button>
		<Button variant="primary" type="submit" loading={s.saving} disabled={!dirty || !s.tagsValid}>{ut('app.saveChanges')}</Button>
	</div>
</form>

<Dialog bind:open={s.confirming} title={nx('nx.deleteTitle')}>
	<p>{tn('notes.deleteConfirm')}</p>
	{#snippet actions()}
		<Button variant="quiet" onclick={() => (s.confirming = false)}>{tn('notes.deleteNo')}</Button>
		<Button variant="danger" loading={s.saving} onclick={() => void s.remove()}>{tn('notes.delete')}</Button>
	{/snippet}
</Dialog>

<style>
	.editor {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-6);
	}
	.title {
		font: var(--kb-type-title);
	}
	.section-head {
		display: flex;
		flex-direction: column;
		gap: 2px;
		margin-bottom: var(--kb-space-3);
	}
	h3 {
		font: var(--kb-type-title-sm);
	}
	.hint {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.fields {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
	}
	.field {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		padding: var(--kb-space-2) var(--kb-space-3) var(--kb-space-3);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface);
		scroll-margin: var(--kb-space-6);
	}
	.field.override {
		border-style: dashed;
		border-color: var(--kb-color-primary);
	}
	.field.open {
		border-color: var(--kb-color-focus);
		box-shadow: 0 0 0 1px var(--kb-color-focus);
	}
	.field-head {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-1) var(--kb-space-2);
		min-height: var(--kb-size-touch-min);
	}
	.label {
		font: var(--kb-type-label);
	}
	.badge {
		padding: 1px var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		font: var(--kb-type-caption);
	}
	.badge.dict {
		background: var(--kb-color-surface-sunken);
		color: var(--kb-color-text-muted);
	}
	.badge.over {
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
	}
	.field-actions {
		display: flex;
		gap: var(--kb-space-1);
		margin-inline-start: auto;
	}
	.value {
		font: var(--kb-type-body);
		overflow-wrap: anywhere;
		display: -webkit-box;
		-webkit-line-clamp: 3;
		line-clamp: 3;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.dictionary .value {
		color: var(--kb-color-text-muted);
	}
	.value.empty {
		color: var(--kb-color-text-muted);
		font-style: italic;
	}
	.more {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
	}
	.actions {
		position: sticky;
		bottom: 0;
		display: flex;
		flex-wrap: wrap;
		justify-content: space-between;
		gap: var(--kb-space-2);
		margin: 0 calc(-1 * var(--kb-space-5));
		padding: var(--kb-space-3) var(--kb-space-5);
		border-top: 1px solid var(--kb-color-border);
		background: var(--kb-color-surface);
	}
</style>
