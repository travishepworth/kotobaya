<script lang="ts">
	import type { Deck } from '../../lib/api/generated';
	import { decksApi } from '../../lib/decks/api';
	import { td, type DeckMessageKey } from '../../lib/decks/messages';

	let items = $state<Deck[]>([]);
	let loaded = $state(false);

	const TYPE_KEYS: Record<string, DeckMessageKey> = {
		user: 'decks.type.user',
		smart: 'decks.type.smart',
		list: 'decks.type.list',
		media: 'decks.type.media'
	};

	$effect(() => {
		void (async () => {
			try {
				const all = (await decksApi.listDecks({})).items;
				items = [...all].sort((a, b) => b.priority - a.priority || a.name.localeCompare(b.name));
			} catch {
				/* toast shown */
			} finally {
				loaded = true;
			}
		})();
	});
</script>

<h1>{td('decks.title')}</h1>
<p><a class="kb-btn" href="/decks/new">{td('decks.new')}</a></p>
{#if !loaded}
	<p>{td('decks.loading')}</p>
{:else if items.length === 0}
	<p>{td('decks.none')}</p>
{:else}
	<ul>
		{#each items as d (d.id)}
			<li>
				<a href={`/decks/${d.id}`}>{d.name}</a>
				<small>
					{td(TYPE_KEYS[d.type] ?? 'decks.type.other')} · {td('decks.items', { n: d.itemCount })} ·
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
