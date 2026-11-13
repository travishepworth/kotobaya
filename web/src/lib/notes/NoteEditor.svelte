<script lang="ts">
	// Edit pane: fields, tags, mnemonic, source sentence, with a core-rendered preview and delete.
	import type { DictEntry, Kanji, Note, NotePatch, NoteType } from '../api/generated';
	import { renderCard, type CardSide, type RenderedCard } from '../core/index';
	import CardView from '../review/CardView.svelte';
	import { fetchEntry, fetchKanji } from '../cards/api';
	import { MEDIA_URL_TEMPLATE, toCoreNoteType, toDictionary, toKanji } from '../review/model';
	import { pushToast } from '../toasts.svelte';
	import { dictFieldValues } from './dict';
	import { notesApi } from './api';
	import { tn } from './messages';

	let {
		note,
		noteType,
		onentry,
		onsaved,
		ondeleted
	}: {
		note: Note;
		noteType: NoteType | undefined;
		onentry: (seq: number, entry: DictEntry) => void;
		onsaved: (n: Note) => void;
		ondeleted: (id: string) => void;
	} = $props();

	let fields = $state<Record<string, string>>({});
	let tagsText = $state('');
	let mnemonic = $state('');
	let sourceSentence = $state('');
	let side = $state<CardSide>('front');
	let tab = $state<'preview' | 'edit'>('preview');
	let kind = $state('');
	let entry = $state<DictEntry | null>(null);
	let kanji = $state<Kanji | null>(null);
	let dictLoading = $state(false);
	let dictFailed = $state(false);
	let saving = $state(false);
	let confirming = $state(false);

	// Reset the form whenever another note (or a fresh server copy of it) arrives.
	$effect(() => {
		fields = { ...note.fields };
		tagsText = note.tags.join(' ');
		mnemonic = note.mnemonic;
		sourceSentence = note.sourceSentence;
		confirming = false;
	});
	$effect(() => {
		void note.id;
		side = 'front';
		tab = 'preview';
		kind = '';
	});

	// Referenced notes render from the dictionary: fetch (cached) whenever another note is opened.
	$effect(() => {
		const seq = note.entrySeq;
		const lit = note.kanjiLiteral;
		entry = null;
		kanji = null;
		dictFailed = false;
		dictLoading = seq !== null || lit !== null;
		if (!dictLoading) return;
		let stale = false;
		void (async () => {
			try {
				if (seq !== null) {
					const e = await fetchEntry(seq);
					if (stale) return;
					entry = e;
					onentry(seq, e);
				}
				if (lit !== null) {
					const k = await fetchKanji(lit);
					if (stale) return;
					kanji = k;
				}
			} catch {
				if (!stale) dictFailed = true;
			} finally {
				if (!stale) dictLoading = false;
			}
		})();
		return () => {
			stale = true;
		};
	});

	/** The note's live card kinds (falls back to the note type's templates). */
	const kinds = $derived.by(() => {
		const live = [...new Set(note.cards.map((c) => c.kind))];
		const order = noteType ? Object.keys(noteType.templates) : [];
		if (live.length === 0) return order;
		// Default (first) kind follows the note type's template order.
		return live.sort((a, b) => {
			const ia = order.indexOf(a);
			const ib = order.indexOf(b);
			return (ia < 0 ? order.length : ia) - (ib < 0 ? order.length : ib);
		});
	});
	const activeKind = $derived(kinds.includes(kind) ? kind : (kinds[0] ?? ''));
	const dictValues = $derived(dictFieldValues(entry, kanji));

	function kindLabel(k: string): string {
		return k === 'meaning' || k === 'reading' ? tn(`notes.kind.${k}`) : k;
	}

	const fieldKeys = $derived.by(() => {
		const keys = noteType ? noteType.fields.map((f) => f.key) : [];
		for (const k of Object.keys(note.fields)) if (!keys.includes(k)) keys.push(k);
		return keys;
	});
	const referenced = $derived(note.entrySeq !== null || note.kanjiLiteral !== null);

	function parseTags(s: string): string[] {
		return [...new Set(s.split(/\s+/).filter((x) => x !== ''))].sort();
	}

	const tagsValid = $derived(parseTags(tagsText).every((x) => x.length <= 64 && x !== 'leech'));

	type Rendered = { ok: true; card: RenderedCard } | { ok: false; detail: string };
	const rendered = $derived.by((): Rendered => {
		if (!noteType) return { ok: false, detail: tn('notes.previewMissingType') };
		try {
			return {
				ok: true,
				card: renderCard({
					noteType: toCoreNoteType(noteType),
					kind: activeKind,
					fields,
					mnemonic,
					dictionary: toDictionary(entry),
					kanji: toKanji(kanji),
					side,
					options: {
						furigana: true,
						pitch: true,
						showMnemonic: true,
						mediaUrlTemplate: MEDIA_URL_TEMPLATE
					}
				})
			};
		} catch (e) {
			return { ok: false, detail: e instanceof Error ? e.message : String(e) };
		}
	});

	async function save(): Promise<void> {
		const patch: NotePatch = {};
		const changed: Record<string, string> = {};
		for (const k of fieldKeys) {
			if ((fields[k] ?? '') !== (note.fields[k] ?? '')) changed[k] = fields[k] ?? '';
		}
		if (Object.keys(changed).length > 0) patch.fields = changed;
		const tags = parseTags(tagsText);
		if (tags.join(' ') !== note.tags.join(' ')) patch.tags = tags;
		if (mnemonic !== note.mnemonic) patch.mnemonic = mnemonic;
		if (sourceSentence !== note.sourceSentence) {
			// One register covers all three source properties: keep the soft references.
			patch.sourceSentence = sourceSentence;
			patch.sourceSentenceId = note.sourceSentenceId;
			patch.sourceMediaId = note.sourceMediaId;
		}
		if (Object.keys(patch).length === 0) {
			pushToast(tn('notes.nothingChanged'), 'info');
			return;
		}
		saving = true;
		try {
			const updated = await notesApi.updateNote({ noteId: note.id, notePatch: patch });
			pushToast(tn('notes.saved'), 'info');
			onsaved(updated);
		} catch {
			/* error toast shown by the client */
		} finally {
			saving = false;
		}
	}

	async function remove(): Promise<void> {
		saving = true;
		try {
			await notesApi.deleteNote({ noteId: note.id });
			pushToast(tn('notes.deleted'), 'info');
			ondeleted(note.id);
		} catch {
			/* error toast shown by the client */
		} finally {
			saving = false;
			confirming = false;
		}
	}
</script>

<section aria-label={tn('notes.edit')}>
	<h2>{tn('notes.edit')}</h2>
	<div class="tabs">
		<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={tab === 'preview'} onclick={() => (tab = 'preview')}>{tn('notes.tabPreview')}</button>
		<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={tab === 'edit'} onclick={() => (tab = 'edit')}>{tn('notes.tabEdit')}</button>
	</div>
	{#if tab === 'preview'}
		<div class="tabs">
			{#each kinds as k (k)}
				<button
					type="button"
					class="kb-btn kb-btn-quiet"
					aria-label={tn('notes.cardKind') + ': ' + kindLabel(k)}
					aria-pressed={activeKind === k}
					onclick={() => (kind = k)}>{kindLabel(k)}</button
				>
			{/each}
		</div>
		<div class="tabs">
			<button
				type="button"
				class="kb-btn kb-btn-quiet"
				aria-pressed={side === 'front'}
				onclick={() => (side = 'front')}>{tn('notes.previewFront')}</button
			>
			<button
				type="button"
				class="kb-btn kb-btn-quiet"
				aria-pressed={side === 'back'}
				onclick={() => (side = 'back')}>{tn('notes.previewBack')}</button
			>
		</div>
		{#if dictLoading}
			<p class="muted">{tn('notes.dictLoading')}</p>
		{:else if dictFailed}
			<p class="err">{tn('notes.dictFailed')}</p>
		{/if}
		{#if rendered.ok}
			<CardView rendered={rendered.card} />
		{:else}
			<p class="err">{tn('notes.previewFailed', { detail: rendered.detail })}</p>
		{/if}
	{:else}
		<form
			onsubmit={(e) => {
				e.preventDefault();
				void save();
			}}
		>
			<fieldset>
				<legend>{tn('notes.fields')}</legend>
				{#if referenced}<p class="muted">{tn('notes.refFieldHint')}</p>{/if}
				{#each fieldKeys as k (k)}
					<label>
						{noteType?.fields.find((f) => f.key === k)?.label ?? k}
						<textarea
							rows="2"
							lang="ja"
							placeholder={referenced && dictValues[k] !== undefined ? tn('notes.fromDictionary', { value: dictValues[k] }) : undefined}
							bind:value={fields[k]}
						></textarea>
						{#if referenced && (fields[k] ?? '') === '' && dictValues[k] !== undefined}
							<span class="dict" lang="ja">{tn('notes.fromDictionary', { value: dictValues[k] })}</span>
						{/if}
					</label>
				{/each}
			</fieldset>
			<label>
				{tn('notes.tags')}
				<input type="text" bind:value={tagsText} />
			</label>
			{#if !tagsValid}<p class="err">{tn('notes.badTag')}</p>{/if}
			<label>
				{tn('notes.mnemonic')}
				<textarea rows="2" bind:value={mnemonic}></textarea>
			</label>
			<label>
				{tn('notes.sourceSentence')}
				<textarea rows="2" lang="ja" bind:value={sourceSentence}></textarea>
			</label>
			<div class="actions">
				<button type="submit" class="kb-btn" disabled={saving || !tagsValid}>
					{saving ? tn('notes.saving') : tn('notes.save')}
				</button>
				{#if !confirming}
					<button type="button" class="kb-btn kb-btn-quiet" onclick={() => (confirming = true)}>
						{tn('notes.delete')}
					</button>
				{/if}
			</div>
		</form>

		{#if confirming}
			<div class="confirm" role="alertdialog" aria-label={tn('notes.delete')}>
				<p>{tn('notes.deleteConfirm')}</p>
				<button type="button" class="kb-btn" disabled={saving} onclick={() => void remove()}>
					{tn('notes.deleteYes')}
				</button>
				<button type="button" class="kb-btn kb-btn-quiet" onclick={() => (confirming = false)}>
					{tn('notes.deleteNo')}
				</button>
			</div>
		{/if}
	{/if}
</section>

<style>
	form {
		display: grid;
		gap: var(--kb-space-3);
	}
	label {
		display: grid;
		gap: var(--kb-space-1);
	}
	fieldset {
		display: grid;
		gap: var(--kb-space-2);
		border: 1px solid var(--kb-border);
		border-radius: var(--kb-radius);
	}
	.actions,
	.tabs {
		display: flex;
		gap: var(--kb-space-2);
	}
	.confirm {
		margin-top: var(--kb-space-2);
		padding: var(--kb-space-2);
		border: 1px solid var(--kb-error);
		border-radius: var(--kb-radius);
	}
	[aria-pressed='true'] {
		font-weight: 700;
	}
	.dict {
		color: var(--kb-text-muted);
		font-size: 0.9em;
	}
	.muted {
		color: var(--kb-text-muted);
		margin: 0;
	}
	.err {
		color: var(--kb-error);
	}
</style>
