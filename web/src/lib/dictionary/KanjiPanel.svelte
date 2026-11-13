<script lang="ts">
	import type { Deck, Kanji, KanjiWord } from '../api/generated';
	import { decksApi, dictionaryApi } from './api';
	import { td } from './messages';

	let {
		literal,
		onopen,
		onclose
	}: { literal: string; onopen: (seq: number) => void; onclose: () => void } = $props();

	const PAGE = 50;

	let kanji = $state<Kanji | null>(null);
	let loadingKanji = $state(true);
	let tab = $state<'frequent' | 'mine'>('frequent');
	let deckId = $state('');
	let decks = $state<Deck[]>([]);
	let words = $state<KanjiWord[]>([]);
	let cursor = $state<string | null>(null);
	let loading = $state(false);
	let gen = 0;

	$effect(() => {
		const lit = literal;
		loadingKanji = true;
		kanji = null;
		let live = true;
		void (async () => {
			try {
				const k = await dictionaryApi.getKanji({ literal: lit });
				if (live) kanji = k;
			} catch {
				/* error toast shown by the client; words are still listed */
			} finally {
				if (live) loadingKanji = false;
			}
		})();
		return () => {
			live = false;
		};
	});

	$effect(() => {
		let live = true;
		void (async () => {
			try {
				const all = (await decksApi.listDecks({})).items;
				if (live) decks = all.filter((d) => d.deletedAt === null);
			} catch {
				/* dropdown stays empty */
			}
		})();
		return () => {
			live = false;
		};
	});

	async function load(reset: boolean): Promise<void> {
		const g = reset ? ++gen : gen;
		if (reset) {
			words = [];
			cursor = null;
		} else if (cursor === null) return;
		loading = true;
		try {
			const page = await dictionaryApi.listKanjiWords({
				literal,
				mine: tab === 'mine' ? true : undefined,
				deckId: deckId === '' ? undefined : deckId,
				limit: PAGE,
				cursor: reset ? undefined : (cursor ?? undefined)
			});
			if (g !== gen) return;
			// Pages are not a snapshot: de-duplicate by seq.
			const seen = new Set(reset ? [] : words.map((w) => w.seq));
			words = [...(reset ? [] : words), ...page.items.filter((w) => !seen.has(w.seq))];
			cursor = page.nextCursor;
		} catch {
			if (g !== gen) return;
			cursor = null;
		} finally {
			if (g === gen) loading = false;
		}
	}

	// Reload whenever the kanji, tab or deck filter changes.
	$effect(() => {
		void literal;
		void tab;
		void deckId;
		void load(true);
	});
</script>

<section class="kanji-panel" aria-label={td('dict.kanjiPanel')}>
	<button type="button" class="kb-btn close" onclick={onclose}>{td('dict.kanjiClose')}</button>
	<h3 lang="ja" class="big">{literal}</h3>
	{#if loadingKanji}
		<p class="muted">{td('dict.kanjiLoading')}</p>
	{:else if kanji}
		<p>{kanji.meanings.join(', ')}</p>
		{#if kanji.onReadings.length > 0}
			<p><strong>{td('dict.kanjiOn')}</strong> <span lang="ja">{kanji.onReadings.join('、')}</span></p>
		{/if}
		{#if kanji.kunReadings.length > 0}
			<p><strong>{td('dict.kanjiKun')}</strong> <span lang="ja">{kanji.kunReadings.join('、')}</span></p>
		{/if}
		<p class="muted">
			{#if kanji.strokeCount !== null}{td('dict.kanjiStrokes', { n: kanji.strokeCount })}{/if}
			{#if kanji.jlpt !== null}· {td('dict.kanjiJlpt', { n: kanji.jlpt })}{/if}
			{#if kanji.grade !== null}· {td('dict.kanjiGrade', { n: kanji.grade })}{/if}
		</p>
	{/if}

	<div class="tabs" role="tablist">
		<button
			type="button"
			role="tab"
			aria-selected={tab === 'frequent'}
			onclick={() => (tab = 'frequent')}>{td('dict.kanjiTabFrequent')}</button
		>
		<button type="button" role="tab" aria-selected={tab === 'mine'} onclick={() => (tab = 'mine')}
			>{td('dict.kanjiTabMine')}</button
		>
	</div>
	<label class="inline">
		{td('dict.kanjiDeckFilter')}
		<select bind:value={deckId}>
			<option value="">{td('dict.kanjiAllDecks')}</option>
			{#each decks as d (d.id)}
				<option value={d.id}>{d.name}</option>
			{/each}
		</select>
	</label>

	{#if loading && words.length === 0}
		<p>{td('dict.searching')}</p>
	{:else if words.length === 0}
		<p class="muted">{td('dict.kanjiNoWords')}</p>
	{:else}
		<ul class="words">
			{#each words as w (w.seq)}
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
		{#if cursor !== null}
			<button type="button" class="kb-btn" disabled={loading} onclick={() => void load(false)}>
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
