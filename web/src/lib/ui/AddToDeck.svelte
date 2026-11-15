<script lang="ts">
	// "Add to deck": a button that opens a checklist of the user's decks (toggle = add / remove), plus chips for the
	// decks that already hold the word or note. Deck membership is per word or note, not per card.
	import { DeckPickerState, type DeckTarget } from '../state/notesDeckPicker.svelte';
	import Button from './Button.svelte';
	import Chip from './Chip.svelte';
	import Dialog from './Dialog.svelte';
	import Icon from './Icon.svelte';
	import TextField from './TextField.svelte';
	import { ut } from './messages';

	interface Props {
		target: DeckTarget;
		variant?: 'tonal' | 'outline' | 'quiet';
		/** Hide the "in decks" chips (a row that shows them itself). */
		hideChips?: boolean;
	}
	let { target, variant = 'outline', hideChips = false }: Props = $props();

	const picker = new DeckPickerState();
	let open = $state(false);

	$effect(() => {
		// Reload when the word or note changes.
		const t = { entrySeq: target.entrySeq, noteId: target.noteId };
		void picker.load(t);
	});
</script>

<div class="add">
	<Button {variant} icon="plus" onclick={() => (open = true)}>{ut('ui.deck.add')}</Button>
	{#if !hideChips && picker.heldDecks.length > 0}
		<ul class="in" aria-label={ut('ui.deck.in')}>
			{#each picker.heldDecks as d (d.id)}<li><Chip kind="deck" label={d.name} /></li>{/each}
		</ul>
	{/if}
</div>

<Dialog bind:open title={ut('ui.deck.title')} sheet>
	{#if !picker.loaded}
		<p class="muted">{ut('ui.loading')}</p>
	{:else if picker.failed}
		<p class="muted">{ut('ui.deck.loadFailed')}</p>
	{:else}
		{#if picker.decks.length === 0}
			<p class="muted">{ut('ui.deck.none')}</p>
		{:else}
			<ul class="list" aria-label={ut('ui.deck.listLabel')}>
				{#each picker.decks as d (d.id)}
					{@const isIn = picker.held.has(d.id)}
					<li>
						<button
							type="button"
							class="row"
							role="checkbox"
							aria-checked={isIn}
							disabled={picker.busy !== null}
							onclick={() => void picker.toggle(d, target)}
						>
							<span class="box" class:on={isIn}>{#if isIn}<Icon name="check" size={16} />{/if}</span>
							<span class="name">{d.name}</span>
						</button>
					</li>
				{/each}
			</ul>
		{/if}
		<!-- A div, not a form: the picker may sit inside the note editor's form (Enter must not save the note). -->
		<div
			class="new"
			role="group"
			aria-label={ut('ui.deck.create')}
			onkeydown={(e) => {
				if (e.key !== 'Enter') return;
				e.preventDefault();
				void picker.createAndAdd(target);
			}}
		>
			<TextField label={ut('ui.deck.newName')} bind:value={picker.newName} />
			<Button variant="tonal" loading={picker.creating} disabled={picker.newName.trim() === ''} onclick={() => void picker.createAndAdd(target)}>{ut('ui.deck.create')}</Button>
		</div>
	{/if}
	{#snippet actions()}
		<Button variant="primary" onclick={() => (open = false)}>{ut('ui.deck.done')}</Button>
	{/snippet}
</Dialog>

<style>
	.add {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2);
	}
	.in {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.muted {
		color: var(--kb-color-text-muted);
	}
	.list {
		display: flex;
		flex-direction: column;
		margin: 0 0 var(--kb-space-4);
		padding: 0;
		list-style: none;
	}
	.row {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		width: 100%;
		min-height: 44px;
		padding: var(--kb-space-2) var(--kb-space-1);
		border: 0;
		background: none;
		color: var(--kb-color-text);
		font: var(--kb-type-body);
		text-align: start;
		cursor: pointer;
	}
	.row:hover:not(:disabled) {
		background: var(--kb-color-surface-hover);
	}
	.box {
		display: grid;
		place-items: center;
		flex: none;
		width: 1.5rem;
		height: 1.5rem;
		border: 2px solid var(--kb-color-border-strong);
		border-radius: var(--kb-radius-xs);
	}
	.box.on {
		border-color: var(--kb-color-primary);
		background: var(--kb-color-primary);
		color: var(--kb-color-on-primary);
	}
	.new {
		display: flex;
		flex-wrap: wrap;
		align-items: end;
		gap: var(--kb-space-2);
	}
</style>
