<script lang="ts">
	import { checkDeckDraft, type DeckDraft, type DeckListCatalogueEntry, type SearchVocabulary } from '../state/decks.svelte';
	import { td } from './messages';
	import QueryEditor from './QueryEditor.svelte';

	let {
		draft = $bindable(),
		isNew,
		lists,
		vocabulary,
		busy,
		serverError = null,
		submitLabel,
		onsubmit,
		ondelete
	}: {
		draft: DeckDraft;
		isNew: boolean;
		lists: DeckListCatalogueEntry[];
		vocabulary: SearchVocabulary;
		busy: boolean;
		serverError?: string | null;
		submitLabel: string;
		onsubmit: () => void;
		ondelete?: () => void;
	} = $props();

	function submit(e: SubmitEvent): void {
		e.preventDefault();
		if (checkDeckDraft(draft)) onsubmit();
	}
</script>

<form onsubmit={submit}>
	{#if isNew}
		<label>
			{td('decks.type')}
			<select bind:value={draft.type}>
				<option value="user">{td('decks.type.user')}</option>
				<option value="smart">{td('decks.type.smart')}</option>
				<option value="list">{td('decks.type.list')}</option>
			</select>
		</label>
	{/if}

	{#if draft.type === 'list' && isNew}
		<label>
			{td('decks.listKey')}
			<select bind:value={draft.listKey} required>
				<option value="">{td('decks.listChoose')}</option>
				{#each lists as l (l.listKey)}
					<option value={l.listKey}>{td('decks.listItems', { title: l.title, n: l.itemCount })}</option>
				{/each}
			</select>
		</label>
	{/if}

	<label>
		{td('decks.name')}
		<input type="text" bind:value={draft.name} maxlength="200" required={draft.type !== 'list' || !isNew} />
	</label>

	{#if draft.type === 'smart'}
		<QueryEditor bind:value={draft.query} {vocabulary} {serverError} />
	{/if}

	<label class="inline"><input type="checkbox" bind:checked={draft.enabled} /> {td('decks.enabled')}</label>
	<label>
		{td('decks.priorityLabel')}
		<input type="text" inputmode="numeric" bind:value={draft.priority} />
	</label>
	<label>
		{td('decks.newPerDay')}
		<input type="text" inputmode="numeric" bind:value={draft.newPerDay} />
	</label>
	<label>
		{td('decks.reviewsPerDay')}
		<input type="text" inputmode="numeric" bind:value={draft.reviewsPerDay} />
	</label>

	<div class="actions">
		<button class="kb-btn" type="submit" disabled={busy}>{submitLabel}</button>
		{#if ondelete}
			<button class="kb-btn kb-btn-quiet" type="button" onclick={ondelete}>{td('decks.delete')}</button>
		{/if}
	</div>
</form>

<style>
	form {
		display: grid;
		gap: var(--kb-space-2);
		max-width: 40rem;
	}
	label {
		display: grid;
		gap: var(--kb-space-1);
	}
	label.inline {
		display: inline-flex;
		gap: var(--kb-space-2);
		align-items: center;
	}
	.actions {
		display: flex;
		gap: var(--kb-space-2);
	}
</style>
