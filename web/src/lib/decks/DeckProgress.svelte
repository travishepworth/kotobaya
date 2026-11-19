<script lang="ts">
	// "400 of 512 words learned · 78%" with a thin bar from `Deck.progress` (ADR-041). Reading (media) decks add the
	// raw text coverage and the stacked words bar when `coverage` is on; it is server-only, so a failed or offline
	// fetch just hides that block.
	import { DeckCoverageState, type Deck } from '../state/decks.svelte';
	import CoverageBar from '../ui/CoverageBar.svelte';
	import ProgressBar from '../ui/ProgressBar.svelte';
	import { td } from './messages';

	interface Props {
		deck: Deck;
		/** Also show the coverage block (media decks only). */
		coverage?: boolean;
		/** Larger figures, for the deck page. */
		large?: boolean;
	}
	let { deck, coverage = false, large = false }: Props = $props();

	const progress = $derived(deck.progress ?? null);
	const share = (n: number, total: number): number => (total > 0 ? Math.round((100 * n) / total) : 0);
	const covState = new DeckCoverageState();
	const cov = $derived(covState.coverage);
	$effect(() => {
		if (coverage && deck.type === 'media') covState.load(deck.id);
	});
</script>

{#if progress || cov}
	<div class="dp" class:large>
		{#if progress}
			<p class="line">
				<span class="words kb-num">{td('decks.progress.words', { learned: progress.learned.toLocaleString(), total: progress.total.toLocaleString() })}</span>
				<span class="pct kb-num">{td('decks.progress.pct', { pct: progress.percent })}</span>
			</p>
			<ProgressBar value={progress.learned} max={Math.max(progress.total, 1)} label={td('decks.progress.label', { name: deck.name })} />
		{/if}
		{#if cov}
			<div class="cov">
				<p class="line">
					<span class="words kb-num">{large ? td('decks.coverage.raw', { pct: cov.tokens.knownPct }) : td('decks.coverage.rawLearning', { pct: cov.tokens.knownPct, learning: share(cov.tokens.learning, cov.tokens.total) })}</span>
				</p>
				<CoverageBar known={cov.tokens.known} learning={cov.tokens.learning} unknown={cov.tokens.unknown} total={cov.tokens.total} height={8} />
				{#if large}
					<ul class="legend">
						<li><i class="k"></i>{td('decks.coverage.known', { pct: share(cov.tokens.known, cov.tokens.total) })}</li>
						<li><i class="l"></i>{td('decks.coverage.learning', { pct: share(cov.tokens.learning, cov.tokens.total) })}</li>
						<li><i class="u"></i>{td('decks.coverage.unknown', { pct: share(cov.tokens.unknown, cov.tokens.total) })}</li>
					</ul>
				{/if}
			</div>
		{/if}
	</div>
{/if}

<style>
	.dp {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		min-width: 0;
	}
	.line {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: var(--kb-space-3);
		margin: 0;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.words {
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.pct {
		flex: none;
		color: var(--kb-color-text);
		font-weight: 600;
	}
	.cov {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		margin-top: var(--kb-space-2);
	}
	.legend {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1) var(--kb-space-4);
		margin: 0;
		padding: 0;
		list-style: none;
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.legend li {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
	}
	.legend i {
		width: 0.625rem;
		height: 0.625rem;
		border-radius: 2px;
	}
	.k {
		background: var(--kb-color-state-review);
	}
	.l {
		background: var(--kb-color-state-learning);
	}
	.u {
		background: var(--kb-color-state-new);
	}
	.large .line {
		font: var(--kb-type-body);
	}
	.large .pct {
		font: var(--kb-type-title-sm);
	}
</style>
