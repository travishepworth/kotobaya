<script lang="ts">
	import DeckForm from '../../../lib/decks/DeckForm.svelte';
	import { td } from '../../../lib/decks/messages';
	import { DeckNewPage } from '../../../lib/state/decks.svelte';

	const s = new DeckNewPage();

	$effect(() => {
		s.start();
	});
</script>

<p><a href="/decks">{td('decks.back')}</a></p>
<h1>{td('decks.new')}</h1>
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
