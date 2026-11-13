<script lang="ts">
	// Struggle overview: hot words (with their recent failures), hot kanji, and the ways in: leeches, confusables, drills.
	import Button from '../../lib/ui/Button.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import LoadError from '../../lib/ui/LoadError.svelte';
	import Page from '../../lib/ui/Page.svelte';
	import Panel from '../../lib/ui/Panel.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import { StrugglePage } from '../../lib/state/struggle.svelte';
	import { noteTitle } from '../../lib/ui/cardState';
	import { tg } from './messages';

	const vm = new StrugglePage();
	$effect(() => {
		vm.start();
	});
	const maxFail = $derived(Math.max(1, ...vm.words.map((w) => w.failures14d)));
</script>

<svelte:head><title>{tg('st.title')} · Kotobaya</title></svelte:head>

<Page title={tg('st.title')} lead={tg('st.lead')}>
	<div class="ways">
		<a class="way" href="/struggle/leeches">
			<span class="wt">{tg('st.leeches')}</span>
			<span class="wb">{tg('st.leechesLead')}</span>
		</a>
		<a class="way" href="/struggle/confusables">
			<span class="wt">{tg('st.confusables')}</span>
			<span class="wb">{tg('st.confusablesLead')}</span>
		</a>
	</div>

	{#if !vm.loaded}
		<div class="grid"><Skeleton shape="block" height="18rem" /><Skeleton shape="block" height="18rem" /></div>
	{:else if vm.loadFailed}
		<LoadError size="page" onretry={() => vm.retry()} />
	{:else if vm.words.length === 0 && vm.kanji.length === 0}
		<EmptyState size="page" art="success" title={tg('st.calmTitle')} body={tg('st.calmBody')} />
	{:else}
		<div class="grid">
			<Panel title={tg('st.hotWords')} subtitle={tg('st.hotWordsLead')} flush>
				{#snippet action()}<Button size="sm" variant="tonal" icon="review" href="/review?drill=hot_words">{tg('st.drill')}</Button>{/snippet}
				{#if vm.words.length === 0}
					<p class="none">{tg('st.hotWordsNone')}</p>
				{:else}
					<ul class="hot">
						{#each vm.words.slice(0, 12) as w (w.noteId)}
							<li>
								<a href="/struggle/word/{w.noteId}">
									<span class="hw" lang="ja">{w.entry?.headword ?? noteTitle(w.note)}</span>
									<span class="gl">{w.entry?.glosses[0] ?? ''}</span>
									<span class="spark" aria-hidden="true"><i style:width="{(w.failures14d / maxFail) * 100}%"></i></span>
									<span class="n kb-num">{tg('st.failures', { n: w.failures14d })}</span>
								</a>
							</li>
						{/each}
					</ul>
				{/if}
			</Panel>
			<Panel title={tg('st.hotKanji')} subtitle={tg('st.hotKanjiLead')}>
				{#if vm.kanji.length === 0}
					<p class="none">{tg('st.hotKanjiNone')}</p>
				{:else}
					<ul class="kanji">
						{#each vm.kanji.slice(0, 16) as k (k.literal)}
							<li title={k.meanings.join(', ')}>
								<span class="lit" lang="ja">{k.literal}</span>
								<span class="km">{k.meanings[0] ?? ''}</span>
								<span class="kw kb-num">{tg('st.kanjiWords', { n: k.wordCount })}</span>
							</li>
						{/each}
					</ul>
				{/if}
			</Panel>
		</div>
	{/if}
</Page>

<style>
	.ways {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 18rem), 1fr));
		gap: var(--kb-space-3);
		margin-bottom: var(--kb-space-5);
	}
	.way {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		padding: var(--kb-space-4) var(--kb-space-5);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
		color: var(--kb-color-text);
		text-decoration: none;
		transition: background-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.way:hover {
		background: var(--kb-color-surface-hover);
	}
	.wt {
		font: var(--kb-type-title-sm);
		color: var(--kb-color-link);
	}
	.wb {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.grid {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: var(--kb-space-5);
		align-items: start;
	}
	@media (min-width: 1024px) {
		.grid {
			grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
		}
	}
	.none {
		padding: 0 var(--kb-space-5) var(--kb-space-3);
		color: var(--kb-color-text-muted);
	}
	.hot {
		margin: 0;
		padding: 0 var(--kb-space-2);
		list-style: none;
	}
	.hot a {
		display: grid;
		grid-template-columns: minmax(5rem, auto) minmax(0, 1fr) 4rem auto;
		align-items: center;
		gap: var(--kb-space-3);
		min-height: 3rem;
		padding: var(--kb-space-2) var(--kb-space-3);
		border-radius: var(--kb-radius-md);
		color: var(--kb-color-text);
		text-decoration: none;
	}
	.hot a:hover {
		background: var(--kb-color-surface-hover);
	}
	.hw {
		font: var(--kb-type-title-sm);
		font-weight: 500;
	}
	.gl {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.spark {
		height: 6px;
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		overflow: hidden;
	}
	.spark i {
		display: block;
		height: 100%;
		background: var(--kb-color-grade-again);
	}
	.n {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		text-align: end;
	}
	@container (max-width: 30rem) {
		.hot a {
			grid-template-columns: minmax(0, 1fr) auto;
		}
		.gl,
		.spark {
			display: none;
		}
	}
	.kanji {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(6.5rem, 1fr));
		gap: var(--kb-space-2);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.kanji li {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 2px;
		padding: var(--kb-space-3) var(--kb-space-2);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-sunken);
		text-align: center;
		min-width: 0;
	}
	.lit {
		font: 2rem/1.2 var(--kb-family-serif);
	}
	.km {
		max-width: 100%;
		font: var(--kb-type-caption);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.kw {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
</style>
