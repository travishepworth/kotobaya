<script lang="ts">
	import { KanjiPanelState } from '../state/dictionary.svelte';
	import { td } from './messages';

	let {
		literal,
		onopen,
		onclose
	}: { literal: string; onopen: (seq: number) => void; onclose: () => void } = $props();

	const vm = new KanjiPanelState(() => literal);
</script>

<section class="kanji-panel" aria-label={td('dict.kanjiPanel')}>
	<button type="button" class="kb-btn close" onclick={onclose}>{td('dict.kanjiClose')}</button>
	<h3 lang="ja" class="big">{literal}</h3>
	{#if vm.loadingKanji}
		<p class="muted">{td('dict.kanjiLoading')}</p>
	{:else if vm.kanji}
		<p>{vm.kanji.meanings.join(', ')}</p>
		{#if vm.kanji.onReadings.length > 0}
			<p><strong>{td('dict.kanjiOn')}</strong> <span lang="ja">{vm.kanji.onReadings.join('、')}</span></p>
		{/if}
		{#if vm.kanji.kunReadings.length > 0}
			<p><strong>{td('dict.kanjiKun')}</strong> <span lang="ja">{vm.kanji.kunReadings.join('、')}</span></p>
		{/if}
		<p class="muted">
			{#if vm.kanji.strokeCount !== null}{td('dict.kanjiStrokes', { n: vm.kanji.strokeCount })}{/if}
			{#if vm.kanji.jlpt !== null}· {td('dict.kanjiJlpt', { n: vm.kanji.jlpt })}{/if}
			{#if vm.kanji.grade !== null}· {td('dict.kanjiGrade', { n: vm.kanji.grade })}{/if}
		</p>
	{/if}

	<div class="tabs" role="tablist">
		<button
			type="button"
			role="tab"
			aria-selected={vm.tab === 'frequent'}
			onclick={() => (vm.tab = 'frequent')}>{td('dict.kanjiTabFrequent')}</button
		>
		<button type="button" role="tab" aria-selected={vm.tab === 'mine'} onclick={() => (vm.tab = 'mine')}
			>{td('dict.kanjiTabMine')}</button
		>
	</div>
	<label class="inline">
		{td('dict.kanjiDeckFilter')}
		<select bind:value={vm.deckId}>
			<option value="">{td('dict.kanjiAllDecks')}</option>
			{#each vm.decks as d (d.id)}
				<option value={d.id}>{d.name}</option>
			{/each}
		</select>
	</label>

	{#if vm.loading && vm.words.length === 0}
		<p>{td('dict.searching')}</p>
	{:else if vm.words.length === 0}
		<p class="muted">{td('dict.kanjiNoWords')}</p>
	{:else}
		<ul class="words">
			{#each vm.words as w (w.seq)}
				<li>
					<button type="button" class="word" onclick={() => onopen(w.seq)}>
						<span class="hw" lang="ja">{w.headword}</span>
						{#if w.headword !== w.headwordReading}<span lang="ja">{w.headwordReading}</span>{/if}
						<span class="gloss">{w.glosses.join('; ')}</span>
						<span class="meta">
							{#if w.freqRank !== null}{td('dict.rank', { n: w.freqRank })} ·{/if}
							{td('dict.status', { status: w.status })}
						</span>
					</button>
					{#if w.decks.length > 0}
						<span class="chips">
							{#each w.decks as d (d.deckId)}
								<span class="chip"
									>{td('dict.kanjiDeckCount', { name: d.name, n: d.occurrenceCount })}</span
								>
							{/each}
						</span>
					{/if}
				</li>
			{/each}
		</ul>
		{#if vm.cursor !== null}
			<button type="button" class="kb-btn" disabled={vm.loading} onclick={() => void vm.load(false)}>
				{td('dict.more')}
			</button>
		{/if}
	{/if}
</section>

<style>
	.kanji-panel {
		border: 1px solid var(--kb-border);
		border-radius: var(--kb-radius);
		padding: var(--kb-space-2);
		margin: var(--kb-space-2) 0;
	}
	.close {
		float: right;
	}
	.big {
		font-size: 2.5rem;
		margin: 0;
	}
	.muted,
	.meta {
		color: var(--kb-text-muted);
		font-size: 0.85rem;
		margin: 0;
	}
	.tabs {
		display: flex;
		gap: var(--kb-space-2);
		margin: var(--kb-space-2) 0;
	}
	.tabs button[aria-selected='true'] {
		font-weight: 700;
		text-decoration: underline;
	}
	.inline {
		display: flex;
		gap: var(--kb-space-2);
		align-items: center;
	}
	.words {
		list-style: none;
		padding: 0;
		margin: var(--kb-space-2) 0;
	}
	.word {
		all: unset;
		display: grid;
		grid-template-columns: auto auto 1fr;
		gap: var(--kb-space-2);
		width: 100%;
		cursor: pointer;
		box-sizing: border-box;
	}
	.word:focus-visible {
		outline: 2px solid var(--kb-focus);
	}
	.word .meta {
		grid-column: 1 / -1;
	}
	.hw {
		font-weight: 700;
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem;
	}
	.chip {
		background: var(--kb-info-bg);
		border-radius: var(--kb-radius);
		padding: 0 0.4rem;
		font-size: 0.8rem;
	}
</style>
