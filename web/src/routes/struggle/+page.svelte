<script lang="ts">
	import type { HotKanji, HotWord } from '../../lib/api/generated';
	import { wordsApi } from '../../lib/review/api';
	import { tg } from './messages';

	let words = $state<HotWord[]>([]);
	let kanji = $state<HotKanji[]>([]);
	let loaded = $state(false);

	$effect(() => {
		void (async () => {
			try {
				const [w, k] = await Promise.all([wordsApi.listHotWords({ limit: 50 }), wordsApi.listHotKanji({ limit: 30 })]);
				words = w.items;
				kanji = k.items;
			} catch {
				/* toast shown */
			} finally {
				loaded = true;
			}
		})();
	});
</script>

<h1>{tg('st.title')}</h1>
<p class="links">
	<a class="kb-btn" href="/struggle/leeches">{tg('st.leeches')}</a>
	<a class="kb-btn" href="/struggle/confusables">{tg('st.confusables')}</a>
</p>
<p class="links">
	<a class="kb-btn" href="/review?drill=hot_words">{tg('st.drillHot')}</a>
	<a class="kb-btn" href="/review?drill=leeches">{tg('st.drillLeeches')}</a>
	<a class="kb-btn" href="/review?drill=confusables">{tg('st.drillConfusables')}</a>
	<small>{tg('st.drillNote')}</small>
</p>

{#if !loaded}
	<p>{tg('st.loading')}</p>
{:else}
	<h2>{tg('st.hotWords')}</h2>
	{#if words.length === 0}
		<p>{tg('st.hotWordsNone')}</p>
	{:else}
		<ul>
			{#each words as w (w.noteId)}
				<li>
					<a href={`/struggle/word/${w.noteId}`}>
						{w.entry?.headword ?? w.note.fields['word'] ?? w.noteId}
					</a>
					{#if w.entry}<span class="reading">{w.entry.headwordReading}</span>
						<span>{w.entry.glosses.slice(0, 3).join('; ')}</span>{/if}
					<small>{tg('st.score', { n: w.score.toFixed(1) })} · {tg('st.failures', { n: w.failures14d })}</small>
				</li>
			{/each}
		</ul>
	{/if}

	<h2>{tg('st.hotKanji')}</h2>
	{#if kanji.length === 0}
		<p>{tg('st.hotKanjiNone')}</p>
	{:else}
		<ul>
			{#each kanji as k (k.literal)}
				<li>
					<strong>{k.literal}</strong>
					<span>{k.meanings.slice(0, 3).join(', ')}</span>
					<small>{tg('st.score', { n: k.score.toFixed(1) })} · {tg('st.kanjiWords', { n: k.wordCount })}</small>
				</li>
			{/each}
		</ul>
	{/if}
{/if}

<style>
	small,
	.reading {
		color: var(--kb-text-muted);
	}
	li {
		margin-bottom: var(--kb-space-2);
	}
	.links {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		align-items: center;
	}
</style>
