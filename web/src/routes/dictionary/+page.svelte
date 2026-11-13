<script lang="ts">
	import { onMount } from 'svelte';
	import GrammarCapture from '../../lib/grammar-capture/GrammarCapture.svelte';
	import { tg } from '../../lib/grammar-capture/messages';
	import KanjiPanel from '../../lib/dictionary/KanjiPanel.svelte';
	import { td } from '../../lib/dictionary/messages';
	import { DictionaryPage, pitchText } from '../../lib/state/dictionary.svelte';

	const vm = new DictionaryPage();

	function submit(e: Event): void {
		e.preventDefault();
		vm.searchNow();
	}

	onMount(() => () => vm.dispose());
</script>

<h1>{td('dict.title')}</h1>

<form class="search" onsubmit={submit}>
	<label>
		{td('dict.search')}
		<input type="search" bind:value={vm.query} oninput={() => vm.queryChanged()} lang="ja" />
	</label>
	<label class="inline">
		<input type="checkbox" bind:checked={vm.commonOnly} onchange={() => void vm.runSearch(true)} />
		{td('dict.commonOnly')}
	</label>
	<p class="muted">{td('dict.searchHelp')}</p>
</form>

<div class="layout">
	<section aria-label={td('dict.results')}>
		{#if vm.searching && vm.items.length === 0}
			<p>{td('dict.searching')}</p>
		{:else if !vm.searched}
			<p class="muted">{td('dict.empty')}</p>
		{:else if vm.items.length === 0}
			<p>{td('dict.none')}</p>
		{:else}
			<ul class="results">
				{#each vm.items as it (it.seq)}
					<li>
						<button
							type="button"
							class="result"
							aria-current={it.seq === vm.selectedSeq ? 'true' : undefined}
							onclick={() => void vm.open(it.seq)}
						>
							<span class="hw" lang="ja">{it.headword}</span>
							{#if it.headword !== it.headwordReading}
								<span class="rd" lang="ja">{it.headwordReading}</span>
							{/if}
							<span class="gloss">{it.glosses.join('; ')}</span>
							<span class="meta">
								{#if it.isCommon}{td('dict.common')}{/if}
								{#if it.freqRank !== null}
									{td('dict.rank', { n: it.freqRank })}{/if}
								{#if it.retired}{td('dict.retired')}{/if}
								{#if it.noteId !== null}{td('dict.hasNote')}{/if}
							</span>
						</button>
					</li>
				{/each}
			</ul>
			{#if vm.cursor !== null}
				<button
					type="button"
					class="kb-btn"
					disabled={vm.searching}
					onclick={() => void vm.runSearch(false)}
				>
					{td('dict.more')}
				</button>
			{/if}
		{/if}
	</section>

	<aside aria-label={td('dict.entry')}>
		{#if vm.loadingEntry}
			<p>{td('dict.loadingEntry')}</p>
		{:else if vm.entry}
			{@const e = vm.entry}
			<h2 lang="ja">{e.headword}</h2>
			<p class="meta">
				{e.pos.join(', ')}
				{#if e.isCommon}· {td('dict.common')}{/if}
				{#if e.retired}· {td('dict.retired')}{/if}
				· {td('dict.status', { status: e.status })}
			</p>
			{#if e.noteId === null}
				<button type="button" class="kb-btn" disabled={vm.adding} onclick={() => void vm.addNote(e)}>
					{vm.adding ? td('dict.adding') : td('dict.addNote')}
				</button>
			{:else}
				<a href="/notes?note={e.noteId}">{td('dict.openNotes')}</a>
			{/if}

			{#if e.kanjiForms.length > 0}
				<h3>{td('dict.kanjiForms')}</h3>
				<p lang="ja">{e.kanjiForms.map((k) => k.text).join('、')}</p>
			{/if}
			<h3>{td('dict.readings')}</h3>
			<p lang="ja">{e.readings.map((r) => r.text).join('、')}</p>
			{#if e.pitch.length > 0}
				<h3>{td('dict.pitch')}</h3>
				<ul>
					{#each e.pitch as p (p.reading)}
						<li><span lang="ja">{p.reading}</span>: {pitchText(p.positions)}</li>
					{/each}
				</ul>
			{/if}
			{#if e.freqs.length > 0}
				<h3>{td('dict.frequency')}</h3>
				<ul>
					{#each e.freqs as f (f.listId)}
						<li>{f.listId}: #{f.rank}</li>
					{/each}
				</ul>
			{/if}
			<h3>{td('dict.senses')}</h3>
			<ol>
				{#each e.senses as s, i (i)}
					<li>
						<span class="meta">{[...s.pos, ...s.misc, ...s.field, ...s.dialect].join(', ')}</span>
						<div>{s.gloss.map((g) => g.text).join('; ')}</div>
						{#if s.info.length > 0}<div class="muted">{s.info.join('; ')}</div>{/if}
					</li>
				{/each}
			</ol>
			{#if e.kanji.length > 0}
				<h3>{td('dict.kanji')}</h3>
				<p lang="ja">
					{#each e.kanji as k (k)}
						<button
							type="button"
							class="kanji-btn"
							aria-label={td('dict.kanjiOpen', { k })}
							aria-pressed={vm.kanjiOpen === k}
							onclick={() => vm.toggleKanji(k)}>{k}</button
						>
					{/each}
				</p>
				{#if vm.kanjiOpen !== null}
					<KanjiPanel
						literal={vm.kanjiOpen}
						onopen={(seq) => void vm.open(seq)}
						onclose={() => vm.closeKanji()}
					/>
				{/if}
			{/if}
			{#if vm.examples && (vm.examples.media.length > 0 || vm.examples.tatoeba.length > 0)}
				<h3>{td('dict.examples')}</h3>
				{#if vm.examples.media.length > 0}
					<h4>{td('dict.examplesMedia')}</h4>
					<ul>
						{#each vm.examples.media as s (s.id)}
							<li>
								<span lang="ja">{s.text}</span>
								<button type="button" onclick={() => vm.toggleCapture(s.id)}
									>{tg('gc.open')}</button
								>
								{#if vm.capturing === s.id}<GrammarCapture sentence={s} onclose={() => vm.closeCapture()} />{/if}
							</li>
						{/each}
					</ul>
				{/if}
				{#if vm.examples.tatoeba.length > 0}
					<h4>{td('dict.examplesTatoeba')}</h4>
					<ul>
						{#each vm.examples.tatoeba as s (s.id)}
							<li>
								<span lang="ja">{s.text}</span>{#if s.translation}<br /><span class="muted"
										>{s.translation}</span
									>{/if}
							</li>
						{/each}
					</ul>
				{/if}
			{/if}
		{:else}
			<p class="muted">{td('dict.selectPrompt')}</p>
		{/if}
	</aside>
</div>

<style>
	.search {
		display: grid;
		gap: var(--kb-space-2);
		max-width: 32rem;
	}
	.inline {
		display: flex;
		gap: var(--kb-space-2);
		align-items: center;
	}
	.layout {
		display: grid;
		grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
		gap: var(--kb-space-4);
		margin-top: var(--kb-space-4);
		align-items: start;
	}
	.results {
		list-style: none;
		margin: 0 0 var(--kb-space-2);
		padding: 0;
		border: 1px solid var(--kb-border);
		border-radius: var(--kb-radius);
	}
	.result {
		all: unset;
		box-sizing: border-box;
		display: grid;
		grid-template-columns: auto auto 1fr;
		gap: var(--kb-space-2);
		width: 100%;
		padding: var(--kb-space-2);
		cursor: pointer;
	}
	.result[aria-current='true'] {
		background: var(--kb-info-bg);
	}
	.result:focus-visible {
		outline: 2px solid var(--kb-focus);
	}
	.hw {
		font-weight: 700;
	}
	.meta {
		grid-column: 1 / -1;
		color: var(--kb-text-muted);
		font-size: 0.85rem;
	}
	aside .meta {
		margin: 0;
	}
	.muted {
		color: var(--kb-text-muted);
		margin: 0;
	}
	.kanji-btn {
		font-size: 1.4rem;
		cursor: pointer;
	}
	h3,
	h4 {
		margin-bottom: 0;
	}
	@media (max-width: 60rem) {
		.layout {
			grid-template-columns: 1fr;
		}
	}
</style>
