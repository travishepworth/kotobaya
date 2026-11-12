<script lang="ts">
	import { goto } from '$app/navigation';
	import type { DeckListCatalogueEntry } from '../../../lib/api/generated';
	import { readProblem, ResponseError } from '../../../lib/api/client';
	import { decksApi } from '../../../lib/decks/api';
	import DeckForm from '../../../lib/decks/DeckForm.svelte';
	import { emptyDraft, parseNumbers, toCreate } from '../../../lib/decks/draft';
	import { td } from '../../../lib/decks/messages';
	import { EMPTY_VOCABULARY, loadVocabulary } from '../../../lib/decks/vocabulary';
	import { pushToast } from '../../../lib/toasts.svelte';

	let draft = $state(emptyDraft());
	let busy = $state(false);
	let lists = $state<DeckListCatalogueEntry[]>([]);
	let vocabulary = $state(EMPTY_VOCABULARY);
	let serverError = $state<string | null>(null);

	$effect(() => {
		void (async () => {
			try {
				vocabulary = await loadVocabulary();
				lists = (await decksApi.listDeckLists()).items;
			} catch {
				/* toast shown; the form still works without suggestions */
			}
		})();
	});

	async function create(): Promise<void> {
		const n = parseNumbers(draft);
		if (!n.ok) return;
		busy = true;
		serverError = null;
		try {
			const deck = await decksApi.createDeck({ deckCreate: toCreate(draft, n) });
			pushToast(td('decks.created'), 'info');
			await goto(`/decks/${deck.id}`, { replaceState: true });
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 422) {
				const p = await readProblem(e.response);
				if (p?.code === 'invalid_query') serverError = p.detail ?? p.code;
			}
		} finally {
			busy = false;
		}
	}
</script>

<p><a href="/decks">{td('decks.back')}</a></p>
<h1>{td('decks.new')}</h1>
<DeckForm
	bind:draft
	isNew={true}
	{lists}
	{vocabulary}
	{busy}
	{serverError}
	submitLabel={td('decks.create')}
	onsubmit={create}
/>
