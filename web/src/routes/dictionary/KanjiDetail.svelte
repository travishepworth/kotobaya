<script lang="ts">
	// One kanji: the literal in the serif face, meanings, readings, and the words that use it (most frequent, or the
	// ones in your decks) with in-deck markers. Words open in the dictionary.
	import Button from '../../lib/ui/Button.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import SegmentedControl from '../../lib/ui/SegmentedControl.svelte';
	import Select from '../../lib/ui/Select.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import { ut } from '../../lib/ui/messages';
	import { td } from '../../lib/dictionary/messages';
	import { KanjiPanelState } from '../../lib/state/dictionary.svelte';
	import { dx } from './messages';

	interface Props {
		literal: string;
		onopen: (seq: number) => void;
	}
	let { literal, onopen }: Props = $props();
	const vm = new KanjiPanelState(() => literal);
	const deckOptions = $derived([{ value: '', label: td('dict.kanjiAllDecks') }, ...vm.decks.map((d) => ({ value: d.id, label: d.name }))]);
</script>

<article class="kanji" aria-label={td('dict.kanjiPanel')}>
	<header class="top">
		<span class="literal" lang="ja">{literal}</span>
		<div class="facts">
			{#if vm.loadingKanji}
				<Skeleton shape="text" count={3} />
			{:else if vm.kanji}
				<p class="meanings">{vm.kanji.meanings.join(', ')}</p>
				<dl class="readings">
					{#if vm.kanji.onReadings.length > 0}
						<div><dt>{dx('dx.kanjiOn')}</dt><dd lang="ja">{vm.kanji.onReadings.join('、')}</dd></div>
					{/if}
					{#if vm.kanji.kunReadings.length > 0}
						<div><dt>{dx('dx.kanjiKun')}</dt><dd lang="ja">{vm.kanji.kunReadings.join('、')}</dd></div>
					{/if}
					{#if vm.kanji.nanori.length > 0}
						<div><dt>{dx('dx.kanjiNanori')}</dt><dd lang="ja">{vm.kanji.nanori.join('、')}</dd></div>
					{/if}
				</dl>
				<p class="tags">
					{#if vm.kanji.strokeCount !== null}<span>{dx('dx.kanjiStrokes', { n: vm.kanji.strokeCount })}</span>{/if}
					{#if vm.kanji.jlpt !== null}<span>{td('dict.kanjiJlpt', { n: vm.kanji.jlpt })}</span>{/if}
					{#if vm.kanji.grade !== null}<span>{td('dict.kanjiGrade', { n: vm.kanji.grade })}</span>{/if}
					{#if vm.kanji.freqRank !== null}<span>{dx('dx.freqLong', { n: vm.kanji.freqRank })}</span>{/if}
				</p>
			{/if}
		</div>
	</header>

	<section class="words" aria-label={dx('dx.kanjiWords', { k: literal })}>
		<div class="filters">
			<SegmentedControl
				label={dx('dx.kanjiWords', { k: literal })}
				bind:value={vm.tab}
				options={[
					{ value: 'frequent', label: td('dict.kanjiTabFrequent') },
					{ value: 'mine', label: td('dict.kanjiTabMine') }
				]}
			/>
			{#if vm.decks.length > 0}
				<div class="deck"><Select label={td('dict.kanjiDeckFilter')} hideLabel options={deckOptions} bind:value={vm.deckId} /></div>
			{/if}
		</div>
		{#if vm.loading && vm.words.length === 0}
			<Skeleton shape="row" count={4} />
		{:else if vm.words.length === 0}
			<EmptyState art="kanji" title={vm.tab === 'mine' ? dx('dx.kanjiNoMine') : dx('dx.kanjiNoWords')} />
		{:else}
			<ul>
				{#each vm.words as w (w.seq)}
					<li>
						<button type="button" class="word" onclick={() => onopen(w.seq)}>
							<span class="hw" lang="ja">{w.headword}</span>
							{#if w.headword !== w.headwordReading}<span class="rd" lang="ja">{w.headwordReading}</span>{/if}
							<span class="gloss">{w.glosses.join('; ')}</span>
							{#if w.decks.length > 0 || w.noteId}
								<span class="marks">
									{#if w.noteId}<span class="mark in">{dx('dx.inNotes')}</span>{/if}
									{#each w.decks.slice(0, 3) as d (d.deckId)}<span class="mark">{d.name}</span>{/each}
								</span>
							{/if}
						</button>
					</li>
				{/each}
			</ul>
			{#if vm.cursor !== null}
				<Button variant="quiet" loading={vm.loading} onclick={() => void vm.load(false)}>{ut('app.loadMore')}</Button>
			{/if}
		{/if}
	</section>
</article>

<style>
	.kanji {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-6);
	}
	.top {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-6);
		align-items: flex-start;
	}
	.literal {
		display: grid;
		place-items: center;
		width: 7.5rem;
		height: 7.5rem;
		flex: none;
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-jp-hero);
		font-family: var(--kb-family-serif);
		font-size: 4.5rem;
		line-height: 1;
	}
	.facts {
		flex: 1 1 16rem;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
		min-width: 0;
	}
	.meanings {
		font: var(--kb-type-title);
	}
	.readings {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		margin: 0;
	}
	.readings div {
		display: grid;
		grid-template-columns: 7.5rem minmax(0, 1fr);
		gap: var(--kb-space-3);
	}
	dt {
		font: var(--kb-type-label);
		color: var(--kb-color-text-muted);
	}
	dd {
		margin: 0;
		font: var(--kb-type-jp-body);
	}
	.tags {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1) var(--kb-space-4);
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.filters {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-3);
		align-items: center;
		margin-bottom: var(--kb-space-3);
	}
	.deck {
		flex: 1 1 12rem;
		max-width: 18rem;
	}
	ul {
		display: flex;
		flex-direction: column;
		margin: 0 calc(-1 * var(--kb-space-3));
		padding: 0;
		list-style: none;
	}
	.word {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: var(--kb-space-1) var(--kb-space-3);
		width: 100%;
		min-height: var(--kb-size-touch-min);
		padding: var(--kb-space-3);
		border: 0;
		border-radius: var(--kb-radius-md);
		background: none;
		text-align: start;
		cursor: pointer;
	}
	.word:hover {
		background: var(--kb-color-surface-hover);
	}
	.hw {
		font: var(--kb-type-title-sm);
		font-weight: 500;
	}
	.rd {
		color: var(--kb-color-text-muted);
	}
	.gloss {
		flex: 1 1 14rem;
		min-width: 0;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.marks {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1);
	}
	.mark {
		padding: 1px var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.mark.in {
		background: var(--kb-color-state-review-container);
		color: var(--kb-color-state-review);
	}
</style>
