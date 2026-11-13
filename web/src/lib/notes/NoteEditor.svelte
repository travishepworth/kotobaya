<script lang="ts">
	// Edit pane: fields, tags, mnemonic, source sentence, with a core-rendered preview and delete.
	import CardView from '../review/CardView.svelte';
	import { NoteEditorState, type DictEntry, type Note, type NoteType } from '../state/notes.svelte';
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

	const s = new NoteEditorState(
		() => note,
		() => noteType,
		() => ({ onentry, onsaved, ondeleted })
	);
</script>

<section aria-label={tn('notes.edit')}>
	<h2>{tn('notes.edit')}</h2>
	<div class="tabs">
		<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={s.tab === 'preview'} onclick={() => (s.tab = 'preview')}>{tn('notes.tabPreview')}</button>
		<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={s.tab === 'edit'} onclick={() => (s.tab = 'edit')}>{tn('notes.tabEdit')}</button>
	</div>
	{#if s.tab === 'preview'}
		<div class="tabs">
			{#each s.kinds as k (k)}
				<button
					type="button"
					class="kb-btn kb-btn-quiet"
					aria-label={tn('notes.cardKind') + ': ' + s.kindLabel(k)}
					aria-pressed={s.activeKind === k}
					onclick={() => (s.kind = k)}>{s.kindLabel(k)}</button
				>
			{/each}
		</div>
		<div class="tabs">
			<button
				type="button"
				class="kb-btn kb-btn-quiet"
				aria-pressed={s.side === 'front'}
				onclick={() => (s.side = 'front')}>{tn('notes.previewFront')}</button
			>
			<button
				type="button"
				class="kb-btn kb-btn-quiet"
				aria-pressed={s.side === 'back'}
				onclick={() => (s.side = 'back')}>{tn('notes.previewBack')}</button
			>
		</div>
		{#if s.ref.loading}
			<p class="muted">{tn('notes.dictLoading')}</p>
		{:else if s.ref.failed}
			<p class="err">{tn('notes.dictFailed')}</p>
		{/if}
		{#if s.rendered.ok}
			<CardView rendered={s.rendered.card} />
		{:else}
			<p class="err">{tn('notes.previewFailed', { detail: s.rendered.detail })}</p>
		{/if}
	{:else}
		<form
			onsubmit={(e) => {
				e.preventDefault();
				void s.save();
			}}
		>
			<fieldset>
				<legend>{tn('notes.fields')}</legend>
				{#if s.referenced}<p class="muted">{tn('notes.refFieldHint')}</p>{/if}
				{#each s.fieldKeys as k (k)}
					<label>
						{s.labelOf(k)}
						<textarea
							rows="2"
							lang="ja"
							placeholder={s.referenced && s.dictValues[k] !== undefined ? tn('notes.fromDictionary', { value: s.dictValues[k] }) : undefined}
							bind:value={s.fields[k]}
						></textarea>
						{#if s.referenced && (s.fields[k] ?? '') === '' && s.dictValues[k] !== undefined}
							<span class="dict" lang="ja">{tn('notes.fromDictionary', { value: s.dictValues[k] })}</span>
						{/if}
					</label>
				{/each}
			</fieldset>
			<label>
				{tn('notes.tags')}
				<input type="text" bind:value={s.tagsText} />
			</label>
			{#if !s.tagsValid}<p class="err">{tn('notes.badTag')}</p>{/if}
			<label>
				{tn('notes.mnemonic')}
				<textarea rows="2" bind:value={s.mnemonic}></textarea>
			</label>
			<label>
				{tn('notes.sourceSentence')}
				<textarea rows="2" lang="ja" bind:value={s.sourceSentence}></textarea>
			</label>
			<div class="actions">
				<button type="submit" class="kb-btn" disabled={s.saving || !s.tagsValid}>
					{s.saving ? tn('notes.saving') : tn('notes.save')}
				</button>
				{#if !s.confirming}
					<button type="button" class="kb-btn kb-btn-quiet" onclick={() => (s.confirming = true)}>
						{tn('notes.delete')}
					</button>
				{/if}
			</div>
		</form>

		{#if s.confirming}
			<div class="confirm" role="alertdialog" aria-label={tn('notes.delete')}>
				<p>{tn('notes.deleteConfirm')}</p>
				<button type="button" class="kb-btn" disabled={s.saving} onclick={() => void s.remove()}>
					{tn('notes.deleteYes')}
				</button>
				<button type="button" class="kb-btn kb-btn-quiet" onclick={() => (s.confirming = false)}>
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
