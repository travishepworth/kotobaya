<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import type { Deck } from '../../../lib/api/generated';
	import { readProblem, ResponseError } from '../../../lib/api/client';
	import { decksApi } from '../../../lib/decks/api';
	import DeckForm from '../../../lib/decks/DeckForm.svelte';
	import DeckItems from '../../../lib/decks/DeckItems.svelte';
	import { parseNumbers, toDraft, toPatch, type DeckDraft } from '../../../lib/decks/draft';
	import { td } from '../../../lib/decks/messages';
	import { EMPTY_VOCABULARY, loadVocabulary } from '../../../lib/decks/vocabulary';
	import { pushToast } from '../../../lib/toasts.svelte';

	const id = $derived(page.params.id ?? '');

	let deck = $state<Deck | null>(null);
	let draft = $state<DeckDraft | null>(null);
	let vocabulary = $state(EMPTY_VOCABULARY);
	let missing = $state(false);
	let busy = $state(false);
	let serverError = $state<string | null>(null);

	async function load(deckId: string): Promise<void> {
		try {
			const d = await decksApi.getDeck({ deckId });
			deck = d;
			draft = toDraft(d);
			if (d.type === 'smart') vocabulary = await loadVocabulary();
		} catch (e) {
			if (e instanceof ResponseError && (e.response.status === 404 || e.response.status === 410)) {
				missing = true;
			}
		}
	}

	$effect(() => {
		if (id) void load(id);
	});

	async function save(): Promise<void> {
		if (!deck || !draft) return;
		const n = parseNumbers(draft);
		if (!n.ok) return;
		busy = true;
		serverError = null;
		try {
			const d = await decksApi.updateDeck({ deckId: id, deckPatch: toPatch(draft, deck.type, n) });
			deck = d;
			draft = toDraft(d);
			pushToast(td('decks.saved'), 'info');
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 422) {
				const p = await readProblem(e.response);
				if (p?.code === 'invalid_query') serverError = p.detail ?? p.code;
			}
		} finally {
			busy = false;
		}
	}

	async function remove(): Promise<void> {
		if (!deck || !confirm(td('decks.confirmDelete', { name: deck.name }))) return;
		try {
			await decksApi.deleteDeck({ deckId: id });
			pushToast(td('decks.deleted'), 'info');
			await goto('/decks', { replaceState: true });
		} catch {
			/* toast shown (media decks: 409 deck_read_only) */
		}
	}
</script>

<p><a href="/decks">{td('decks.back')}</a></p>
{#if missing}
	<p>{td('decks.notFound')}</p>
{:else if deck && draft}
	<h1>{deck.name}</h1>
	{#if deck.type === 'media'}<p class="help">{td('decks.readOnly')}</p>{/if}
	<DeckForm
		bind:draft
		isNew={false}
		lists={[]}
		{vocabulary}
		{busy}
		{serverError}
		submitLabel={td('decks.save')}
		onsubmit={save}
		ondelete={deck.type === 'media' ? undefined : remove}
	/>
	<section>
		<h2>{td('decks.coverage')}</h2>
		<p class="help">{td('decks.coverageNA')}</p>
	</section>
	<DeckItems {deck} />
{:else}
	<p>{td('decks.loading')}</p>
{/if}

<style>
	.help {
		color: var(--kb-text-muted);
	}
</style>
