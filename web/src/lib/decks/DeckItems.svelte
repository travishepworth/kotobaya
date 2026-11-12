<script lang="ts">
	import type { Deck, DeckItem } from '../api/generated';
	import { pushToast } from '../toasts.svelte';
	import { decksApi } from './api';
	import { td } from './messages';

	let { deck }: { deck: Deck } = $props();

	const editable = $derived(deck.type === 'user');

	let items = $state<DeckItem[]>([]);
	let cursor = $state<string | null>(null);
	let loaded = $state(false);
	let entrySeq = $state('');
	let noteId = $state('');
	let busy = $state(false);

	async function load(more: boolean): Promise<void> {
		try {
			const page = await decksApi.listDeckItems({
				deckId: deck.id,
				limit: 50,
				cursor: more ? (cursor ?? undefined) : undefined
			});
			items = more ? [...items, ...page.items] : page.items;
			cursor = page.nextCursor;
		} catch {
			/* toast shown */
		} finally {
			loaded = true;
		}
	}

	$effect(() => {
		// Smart decks have no items (the server answers 409 not_applicable).
		if (deck.type !== 'smart') void load(false);
		else loaded = true;
	});

	async function add(e: SubmitEvent): Promise<void> {
		e.preventDefault();
		const seq = entrySeq.trim();
		const note = noteId.trim();
		if (seq === '' && note === '') return;
		if (seq !== '' && !/^\d+$/.test(seq)) {
			pushToast(td('decks.invalidNumber', { field: td('decks.itemsAddEntry') }), 'error');
			return;
		}
		busy = true;
		try {
			await decksApi.addDeckItems({
				deckId: deck.id,
				deckItemsAdd: {
					items: [seq !== '' ? { entrySeq: Number(seq) } : { noteId: note }]
				}
			});
			entrySeq = '';
			noteId = '';
			pushToast(td('decks.itemsAdded'), 'info');
			await load(false);
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}

	async function remove(item: DeckItem): Promise<void> {
		try {
			await decksApi.removeDeckItem({ deckId: deck.id, itemKey: item.itemKey });
			items = items.filter((x) => x.itemKey !== item.itemKey);
		} catch {
			/* toast shown */
		}
	}

	function label(item: DeckItem): string {
		if (item.entry) return `${item.entry.headword} (${item.entry.headwordReading}): ${item.entry.glosses.slice(0, 3).join('; ')}`;
		if (item.noteId) return td('decks.itemNote', { id: item.noteId });
		return td('decks.itemEntry', { seq: item.entrySeq ?? '' });
	}
</script>

<section>
	<h2>{td('decks.itemsTitle')}</h2>
	{#if deck.type === 'smart'}
		<p class="help">{td('decks.itemsSmart')}</p>
	{:else}
		{#if deck.type === 'list'}
			<p class="help">{td('decks.itemsList')}</p>
			{#if deck.itemCount === 0}<p class="help">{td('decks.itemsListZero')}</p>{/if}
		{:else if deck.type === 'media'}
			<p class="help">{td('decks.itemsMedia')}</p>
		{/if}

		{#if !loaded}
			<p>{td('decks.loading')}</p>
		{:else if items.length === 0}
			<p>{td('decks.itemsNone')}</p>
		{:else}
			<ul>
				{#each items as item (item.itemKey)}
					<li>
						<span lang="ja">{label(item)}</span>
						{#if editable}
							<button class="kb-btn kb-btn-quiet" type="button" onclick={() => remove(item)}>
								{td('decks.itemRemove')}
							</button>
						{/if}
					</li>
				{/each}
			</ul>
			{#if cursor}
				<button class="kb-btn kb-btn-quiet" type="button" onclick={() => load(true)}>
					{td('decks.itemsMore')}
				</button>
			{/if}
		{/if}

		{#if editable}
			<form onsubmit={add}>
				<h3>{td('decks.itemsAddHeading')}</h3>
				<label>
					{td('decks.itemsAddEntry')}
					<input type="text" inputmode="numeric" bind:value={entrySeq} disabled={noteId !== ''} />
				</label>
				<label>
					{td('decks.itemsAddNote')}
					<input type="text" bind:value={noteId} disabled={entrySeq !== ''} />
				</label>
				<button class="kb-btn" type="submit" disabled={busy}>{td('decks.itemsAdd')}</button>
			</form>
		{/if}
	{/if}
</section>

<style>
	.help {
		color: var(--kb-text-muted);
	}
	form {
		display: grid;
		gap: var(--kb-space-2);
		max-width: 28rem;
	}
	label {
		display: grid;
		gap: var(--kb-space-1);
	}
</style>
