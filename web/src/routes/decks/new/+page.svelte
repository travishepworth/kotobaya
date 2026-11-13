<script lang="ts">
	import Page from '../../../lib/ui/Page.svelte';
	import { td } from '../../../lib/decks/messages';
	import { DeckNewPage } from '../../../lib/state/decks.svelte';
	import DeckForm from '../DeckForm.svelte';

	const s = new DeckNewPage();
	$effect(() => {
		s.start();
	});
</script>

<svelte:head><title>{td('decks.new')} · Kotobaya</title></svelte:head>

<Page title={td('decks.new')} back={{ href: '/decks', label: td('decks.title') }} width="narrow">
	<DeckForm
		bind:draft={s.draft}
		isNew={true}
		lists={s.lists}
		vocabulary={s.vocabulary}
		busy={s.busy}
		serverError={s.serverError}
		submitLabel={td('decks.create')}
		onsubmit={() => void s.create()}
	/>
</Page>
