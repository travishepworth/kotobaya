<script lang="ts">
	import { page } from '$app/state';
	import DeckForm from '../../../lib/decks/DeckForm.svelte';
	import DeckItems from '../../../lib/decks/DeckItems.svelte';
	import { td } from '../../../lib/decks/messages';
	import { DeckEditPage } from '../../../lib/state/decks.svelte';

	const id = $derived(page.params.id ?? '');
	const s = new DeckEditPage();

	$effect(() => {
		if (id) s.load(id);
	});

	function remove(): void {
		if (!s.deck || !confirm(td('decks.confirmDelete', { name: s.deck.name }))) return;
		void s.remove();
	}
</script>

<p><a href="/decks">{td('decks.back')}</a></p>
{#if s.missing}
	<p>{td('decks.notFound')}</p>
{:else if s.deck && s.draft}
	<h1>{s.deck.name}</h1>
	{#if s.deck.type === 'media'}<p class="help">{td('decks.readOnly')}</p>{/if}
	<DeckForm
		bind:draft={s.draft}
		isNew={false}
		lists={[]}
		vocabulary={s.vocabulary}
		busy={s.busy}
		serverError={s.serverError}
		submitLabel={td('decks.save')}
		onsubmit={() => void s.save()}
		ondelete={s.deck.type === 'media' ? undefined : remove}
	/>
	<section>
		<h2>{td('decks.coverage')}</h2>
		<p class="help">{td('decks.coverageNA')}</p>
	</section>
	<DeckItems deck={s.deck} />
{:else}
	<p>{td('decks.loading')}</p>
{/if}

<style>
	.help {
		color: var(--kb-text-muted);
	}
</style>
