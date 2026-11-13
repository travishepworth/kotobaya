<script lang="ts">
	import { td } from '../../lib/decks/messages';
	import { DECK_TYPE_KEYS, DecksListPage } from '../../lib/state/decks.svelte';

	const s = new DecksListPage();

	$effect(() => {
		s.start();
	});
</script>

<h1>{td('decks.title')}</h1>
<p><a class="kb-btn" href="/decks/new">{td('decks.new')}</a></p>
{#if !s.loaded}
	<p>{td('decks.loading')}</p>
{:else if s.items.length === 0}
	<p>{td('decks.none')}</p>
{:else}
	<ul>
		{#each s.items as d (d.id)}
			<li>
				<a href={`/decks/${d.id}`}>{d.name}</a>
				<small>
					{td(DECK_TYPE_KEYS[d.type] ?? 'decks.type.other')} · {td('decks.items', { n: d.itemCount })} ·
					{td('decks.priority', { n: d.priority })}
					{#if !d.enabled}· {td('decks.disabled')}{/if}
				</small>
			</li>
		{/each}
	</ul>
{/if}

<style>
	small {
		color: var(--kb-text-muted);
	}
	li {
		margin-bottom: var(--kb-space-2);
	}
</style>
