<script lang="ts">
	import { onMount } from 'svelte';
	import type { Card, Deck, NoteType } from '../../lib/api/generated';
	import { ResponseError, readProblem } from '../../lib/api/client';
	import { EMPTY_VOCABULARY, loadVocabulary } from '../../lib/decks/vocabulary';
	import type { SearchVocabulary } from '../../lib/core/index';
	import { cardsApi, decksApi, noteTypesApi } from '../../lib/cards/api';
	import BulkBar from '../../lib/cards/BulkBar.svelte';
	import CardPreview from '../../lib/cards/CardPreview.svelte';
	import CardTable from '../../lib/cards/CardTable.svelte';
	import SearchBox from '../../lib/cards/SearchBox.svelte';
	import { tc } from '../../lib/cards/messages';
	import { PAGE_SIZE, type Selector } from '../../lib/cards/model';

	let draft = $state('');
	/** The query the shown results (and "all matching") belong to. */
	let appliedQuery = $state('');
	let cards = $state<Card[]>([]);
	let cursor = $state<string | null>(null);
	let loading = $state(false);
	let loadedOnce = $state(false);
	let serverError = $state<string | null>(null);
	let vocabulary = $state<SearchVocabulary>(EMPTY_VOCABULARY);
	let userDecks = $state<Deck[]>([]);
	let noteTypes = $state<Map<string, NoteType>>(new Map());
	let selected = $state<Set<string>>(new Set());
	let allMatching = $state(false);
	let activeId = $state<string | null>(null);
	/** Headwords of dictionary notes, filled lazily from the preview's entry fetches. */
	let headwords = $state<Map<number, string>>(new Map());
	/** Bumped for every new search so a slow page of an older query is dropped. */
	let generation = 0;

	const active = $derived(cards.find((c) => c.id === activeId) ?? null);
	const selector = $derived<Selector>(allMatching ? { query: appliedQuery } : { ids: [...selected] });
	const hasSelection = $derived(allMatching || selected.size > 0);
	const scopeText = $derived(
		allMatching ? tc('bulk.scope.query') : tc('bulk.scope.ids', { n: selected.size })
	);

	async function loadPage(reset: boolean): Promise<void> {
		if (loading && !reset) return;
		if (!reset && cursor === null) return;
		const gen = reset ? ++generation : generation;
		loading = true;
		try {
			const page = await cardsApi.listCards({
				query: appliedQuery === '' ? undefined : appliedQuery,
				limit: PAGE_SIZE,
				cursor: reset ? undefined : (cursor ?? undefined)
			});
			if (gen !== generation) return;
			serverError = null;
			cards = reset ? page.items : [...cards, ...page.items];
			cursor = page.nextCursor;
		} catch (e) {
			if (gen !== generation) return;
			if (e instanceof ResponseError && e.response.status === 422) {
				serverError = (await readProblem(e.response))?.detail ?? 'invalid_query';
			}
			cursor = null;
		} finally {
			if (gen === generation) {
				loading = false;
				loadedOnce = true;
			}
		}
	}

	function search(): void {
		appliedQuery = draft.trim();
		selected = new Set();
		allMatching = false;
		activeId = null;
		cards = [];
		cursor = null;
		void loadPage(true);
	}

	function toggle(c: Card): void {
		const next = new Set(selected);
		if (!next.delete(c.id)) next.add(c.id);
		selected = next;
	}

	function togglePage(): void {
		const all = cards.length > 0 && cards.every((c) => selected.has(c.id));
		selected = all ? new Set() : new Set(cards.map((c) => c.id));
	}

	function clearSelection(): void {
		selected = new Set();
		allMatching = false;
	}

	/** After a bulk call: reload the list, keep the selection (so the result stays on screen). */
	function applied(): void {
		void loadPage(true);
	}

	onMount(() => {
		void (async () => {
			try {
				vocabulary = await loadVocabulary();
				userDecks = (await decksApi.listDecks({ type: 'user' })).items;
				noteTypes = new Map((await noteTypesApi.listNoteTypes({})).items.map((n) => [n.id, n]));
			} catch {
				/* toast shown; the page still works without completions */
			}
		})();
		search();
	});
</script>

<h1>{tc('cards.title')}</h1>

<SearchBox bind:value={draft} {vocabulary} {serverError} onsubmit={search} />

<div class="layout">
	<div class="main">
		{#if !loadedOnce}
			<p>{tc('cards.loading')}</p>
		{:else if cards.length === 0 && !serverError}
			<p>{tc('cards.none')}</p>
		{:else}
			<CardTable
				{cards}
				{selected}
				{allMatching}
				{activeId}
				hasMore={cursor !== null}
				{headwords}
				onactivate={(c) => (activeId = c.id)}
				ontoggle={toggle}
				ontogglepage={togglePage}
				onnearend={() => void loadPage(false)}
			/>
			<p class="muted">
				{tc('cards.loadedCount', { n: cards.length })}{#if cursor !== null}
					· {tc('cards.moreAvailable')}{/if}
			</p>
		{/if}

		{#if hasSelection}
			<p class="sel">
				{allMatching ? tc('cards.selectedAll') : tc('cards.selected', { n: selected.size })}
				{#if !allMatching && appliedQuery !== ''}
					<button type="button" class="kb-btn kb-btn-quiet" onclick={() => (allMatching = true)}>
						{tc('cards.selectAllMatching')}
					</button>
				{/if}
				<button type="button" class="kb-btn kb-btn-quiet" onclick={clearSelection}>
					{tc('cards.clearSelection')}
				</button>
			</p>
			<BulkBar {selector} {scopeText} decks={userDecks} onapplied={applied} />
		{:else if appliedQuery !== '' && cards.length > 0}
			<p class="sel">
				<button type="button" class="kb-btn kb-btn-quiet" onclick={() => (allMatching = true)}>
					{tc('cards.selectAllMatching')}
				</button>
			</p>
		{/if}
	</div>

	<aside>
		<h2>{tc('cards.preview')}</h2>
		{#if active}
			<CardPreview
				card={active}
				onheadword={(seq, h) => {
					if (headwords.get(seq) !== h) headwords = new Map(headwords).set(seq, h);
				}}
				 noteType={active.note ? noteTypes.get(active.note.noteTypeId) : undefined} />
		{:else}
			<p class="muted">{tc('cards.previewNone')}</p>
		{/if}
	</aside>
</div>

<style>
	.layout {
		display: grid;
		grid-template-columns: minmax(0, 2fr) minmax(16rem, 1fr);
		gap: var(--kb-space-4);
		margin-top: var(--kb-space-4);
		align-items: start;
	}
	.main {
		display: grid;
		gap: var(--kb-space-3);
		min-width: 0;
	}
	.muted {
		color: var(--kb-text-muted);
		margin: 0;
	}
	.sel {
		margin: 0;
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		align-items: center;
	}
	h2 {
		font-size: 1rem;
	}
	@media (max-width: 60rem) {
		.layout {
			grid-template-columns: 1fr;
		}
	}
</style>
