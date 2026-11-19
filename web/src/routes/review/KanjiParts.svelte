<script lang="ts">
	// Back of a vocab card (ADR-041): one tile per kanji of the word - the character large, your keyword and the
	// meanings, the reading this word uses highlighted and the other ON/KUN readings dimmed. A tap opens the kanji
	// in the dictionary. The data is `QueueItem.kanji_parts` as sent (built by the shared core); nothing is derived.
	import type { KanjiPart } from '../../lib/state/review.svelte';
	import { dictionarySearchHref } from '../../lib/state/dictionaryLink';
	import { rv } from './messages';

	let { parts }: { parts: KanjiPart[] } = $props();

	const MAX_OTHER = 4;
	const label = (r: string): string => r.replaceAll('.', '·');

	interface Chip {
		text: string;
		used: boolean;
		type: 'on' | 'kun';
	}
	function chips(p: KanjiPart): Chip[] {
		const u = p.usedReading;
		const used = u && (u.type === 'on' || u.type === 'kun') ? u : null;
		const mk = (type: 'on' | 'kun', list: string[]): Chip[] => {
			const isUsed = (r: string): boolean => used !== null && used.type === type && r === used.reading;
			const hit = list.filter(isUsed);
			const rest = list.filter((r) => !isUsed(r)).slice(0, MAX_OTHER);
			return [...hit.map((r) => ({ text: label(r), used: true, type })), ...rest.map((r) => ({ text: label(r), used: false, type }))];
		};
		return [...mk('on', p.on), ...mk('kun', p.kun)];
	}
	/** The keyword leads (else the first meaning); up to two other meanings follow. */
	function gloss(p: KanjiPart): { title: string | null; rest: string } {
		const title = p.keyword ?? p.meanings[0] ?? null;
		return { title, rest: p.meanings.filter((m) => m !== title).slice(0, 2).join(', ') };
	}
</script>

{#if parts.length > 0}
	<ul class="parts" aria-label={rv('rv.kanji')}>
		{#each parts as p (p.literal)}
			{@const g = gloss(p)}
			<li>
				<a class="tile" href={dictionarySearchHref(p.literal)} aria-label={rv('rv.kanji.open', { c: p.literal })}>
					<span class="lit" lang="ja">{p.literal}</span>
					<span class="info">
						<span class="kw">{g.title ?? rv('rv.kanji.noMeaning')}</span>
						{#if g.rest}<span class="mn">{g.rest}</span>{/if}
						<span class="rds" lang="ja">
							{#each chips(p) as c (c.type + c.text)}
								<span class="rd" class:used={c.used}>{c.text}</span>
							{/each}
						</span>
					</span>
				</a>
			</li>
		{/each}
	</ul>
{/if}

<style>
	.parts {
		list-style: none;
		margin: var(--kb-space-4) auto 0;
		padding: 0;
		max-width: 40rem;
		display: flex;
		flex-wrap: wrap;
		justify-content: center;
		gap: var(--kb-space-2);
	}
	.parts li {
		flex: 1 1 13rem;
		max-width: 19rem;
		min-width: 0;
	}
	.tile {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		height: 100%;
		padding: var(--kb-space-2) var(--kb-space-3);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
		color: var(--kb-color-text);
		text-decoration: none;
		text-align: start;
	}
	.tile:hover {
		background: var(--kb-color-surface-hover);
	}
	.tile:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
	.lit {
		flex: none;
		font-size: 2.5rem;
		line-height: 1;
	}
	.info {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.kw {
		font: var(--kb-type-title-sm);
		overflow-wrap: anywhere;
	}
	.mn {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		overflow-wrap: anywhere;
	}
	.rds {
		display: flex;
		flex-wrap: wrap;
		gap: 2px var(--kb-space-1);
		margin-top: 2px;
	}
	.rd {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		opacity: 0.75;
	}
	.rd.used {
		opacity: 1;
		padding: 0 var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
		font-weight: 600;
	}
</style>
