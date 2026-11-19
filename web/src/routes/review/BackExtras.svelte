<script lang="ts">
	// Review back side extras (ADR-029): kanji example words, or the decks holding a vocab note.
	import type { QueueItem } from '../../lib/state/review.svelte';
	import { dictionaryHref } from '../../lib/state/dictionaryLink';
	import { rv } from './messages';

	let { item }: { item: QueueItem } = $props();

	const isKanji = $derived(item.card.note?.kanjiLiteral != null);
	const examples = $derived(isKanji ? (item.kanjiExamples ?? []).slice(0, 3) : []);
	const decks = $derived(isKanji ? [] : (item.decks ?? []).slice(0, 3));
	const more = $derived(Math.max(0, (item.deckCount ?? 0) - decks.length));
</script>

{#if examples.length > 0}
	<ul class="examples" aria-label={rv('rv.examples')}>
		{#each examples as ex (ex.seq)}
			<li>
				<a href={dictionaryHref(ex.seq)} target="_blank" rel="noopener">
					<span class="hw" lang="ja">{ex.headword}</span>
					<span class="rd" lang="ja">{ex.reading}</span>
					{#if ex.readingType === 'on' || ex.readingType === 'kun'}
						<span class="chip">{rv(ex.readingType === 'on' ? 'rv.tag.on' : 'rv.tag.kun')}</span>
					{/if}
					<span class="gl">{ex.gloss}</span>
				</a>
			</li>
		{/each}
	</ul>
{:else if decks.length > 0}
	<ul class="decks" aria-label={rv('rv.decks')}>
		{#each decks as d (d.id)}
			<li><a class="chip" href="/decks/{d.id}" target="_blank" rel="noopener">{d.name}</a></li>
		{/each}
		{#if more > 0}<li class="more">{rv('rv.decks.more', { n: more })}</li>{/if}
	</ul>
{/if}

<style>
	ul {
		list-style: none;
		margin: var(--kb-space-4) auto 0;
		padding: 0;
		max-width: 32rem;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		font: var(--kb-type-body-sm);
	}
	.decks {
		flex-direction: row;
		flex-wrap: wrap;
		justify-content: center;
		align-items: center;
		gap: var(--kb-space-2);
	}
	.examples a {
		display: flex;
		align-items: baseline;
		gap: var(--kb-space-2);
		color: var(--kb-color-text);
		text-decoration: none;
	}
	.examples li {
		min-width: 0;
	}
	.examples a {
		min-width: 0;
	}
	/* Expression + reading stay on one line; the meaning ellipsizes first. */
	.hw,
	.rd,
	.chip {
		flex: none;
		white-space: nowrap;
	}
	.hw {
		font-weight: 600;
	}
	.rd,
	.gl,
	.more {
		color: var(--kb-color-text-muted);
	}
	.gl {
		flex: 1 1 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		min-width: 0;
	}
	.chip {
		font: var(--kb-type-caption);
		padding: 0 var(--kb-space-2);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		color: var(--kb-color-text-muted);
		text-decoration: none;
	}
	a:hover .hw {
		text-decoration: underline;
	}
</style>
