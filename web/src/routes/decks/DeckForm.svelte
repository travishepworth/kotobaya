<script lang="ts">
	// Create / edit a deck: kind (new decks), name, the smart-deck query, daily limits, priority, include in study.
	import Button from '../../lib/ui/Button.svelte';
	import Panel from '../../lib/ui/Panel.svelte';
	import QueryField from '../../lib/ui/QueryField.svelte';
	import Select from '../../lib/ui/Select.svelte';
	import Switch from '../../lib/ui/Switch.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { td } from '../../lib/decks/messages';
	import { checkDeckDraft, type DeckDraft, type DeckListCatalogueEntry, type SearchVocabulary } from '../../lib/state/decks.svelte';
	import { dk } from './messages';

	interface Props {
		draft: DeckDraft;
		isNew: boolean;
		lists: DeckListCatalogueEntry[];
		vocabulary: SearchVocabulary;
		busy: boolean;
		serverError?: string | null;
		submitLabel: string;
		onsubmit: () => void;
	}
	let { draft = $bindable(), isNew, lists, vocabulary, busy, serverError = null, submitLabel, onsubmit }: Props = $props();

	function submit(e: SubmitEvent): void {
		e.preventDefault();
		if (checkDeckDraft(draft)) onsubmit();
	}
	const TYPES = ['user', 'smart', 'list'] as const;
</script>

<form class="form" onsubmit={submit}>
	<Panel title={dk('dk.basics')}>
		<div class="stack">
			{#if isNew}
				<fieldset class="types">
					<legend>{dk('dk.typeLabel')}</legend>
					{#each TYPES as t (t)}
						<label class="type" class:on={draft.type === t}>
							<input type="radio" name="deck-type" value={t} bind:group={draft.type} />
							<span class="tl">{td(`decks.type.${t}`)}</span>
							<span class="th">{dk(`dk.type.${t}.hint`)}</span>
						</label>
					{/each}
				</fieldset>
			{/if}
			{#if draft.type === 'list' && isNew}
				<Select
					label={td('decks.listKey')}
					bind:value={draft.listKey}
					options={[{ value: '', label: td('decks.listChoose') }, ...lists.map((l) => ({ value: l.listKey, label: td('decks.listItems', { title: l.title, n: l.itemCount }) }))]}
				/>
			{/if}
			<TextField label={td('decks.name')} bind:value={draft.name} maxlength={200} required={draft.type !== 'list' || !isNew} />
			{#if draft.type === 'smart'}
				<QueryField
					label={td('decks.query')}
					bind:value={draft.query}
					{vocabulary}
					hint={td('decks.queryHelp')}
					{serverError}
					completionsLabel={td('decks.suggestions')}
					multiline
				/>
			{/if}
			<Switch label={dk('dk.enabled')} description={dk('dk.enabledHint')} bind:checked={draft.enabled} />
		</div>
	</Panel>

	<Panel title={dk('dk.limits')} subtitle={dk('dk.limitsHint')}>
		<div class="grid">
			<TextField label={dk('dk.newPerDay')} inputmode="numeric" bind:value={draft.newPerDay} />
			<TextField label={dk('dk.reviewsPerDay')} inputmode="numeric" bind:value={draft.reviewsPerDay} />
			<TextField label={dk('dk.priorityLabel')} hint={dk('dk.priorityHint')} inputmode="numeric" bind:value={draft.priority} />
		</div>
	</Panel>

	<div class="actions">
		<Button variant="primary" type="submit" loading={busy}>{submitLabel}</Button>
	</div>
</form>

<style>
	.form {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
	}
	.stack {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 12rem), 1fr));
		gap: var(--kb-space-4);
		align-items: start;
	}
	.types {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 11rem), 1fr));
		gap: var(--kb-space-2);
		margin: 0;
		padding: 0;
		border: 0;
	}
	legend {
		margin-bottom: var(--kb-space-2);
		font: var(--kb-type-label);
	}
	.type {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 2px;
		padding: var(--kb-space-3) var(--kb-space-4);
		border: 1px solid var(--kb-color-border-strong);
		border-radius: var(--kb-radius-md);
		cursor: pointer;
	}
	.type.on {
		border-color: var(--kb-color-primary);
		background: var(--kb-color-primary-container);
		box-shadow: inset 0 0 0 1px var(--kb-color-primary);
	}
	.type input {
		position: absolute;
		opacity: 0;
		inset: 0;
		cursor: pointer;
	}
	.type:has(input:focus-visible) {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
	.tl {
		font: var(--kb-type-label);
	}
	.th {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.actions {
		display: flex;
		justify-content: flex-end;
	}
</style>
