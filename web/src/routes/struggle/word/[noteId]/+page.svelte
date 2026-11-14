<script lang="ts">
	// One troublesome word: what it is, how often it failed lately, a mnemonic to rewrite, sentences to turn into cards,
	// and the words it gets confused with.
	import { page } from '$app/state';
	import Button from '../../../../lib/ui/Button.svelte';
	import EmptyState from '../../../../lib/ui/EmptyState.svelte';
	import Page from '../../../../lib/ui/Page.svelte';
	import Panel from '../../../../lib/ui/Panel.svelte';
	import Skeleton from '../../../../lib/ui/Skeleton.svelte';
	import TextArea from '../../../../lib/ui/TextArea.svelte';
	import { StruggleWordPage } from '../../../../lib/state/struggle.svelte';
	import { dictionaryHref } from '../../../../lib/state/dictionaryLink';
	import { noteTitle } from '../../../../lib/ui/cardState';
	import { tg, tgEnum } from '../../messages';

	const noteId = $derived(page.params.noteId ?? '');
	const vm = new StruggleWordPage();
	$effect(() => {
		vm.load(noteId);
	});
	const title = $derived(vm.word ? (vm.word.entry?.headword ?? noteTitle(vm.word.note)) : tg('st.title'));
</script>

<svelte:head><title>{title} · Kotobaya</title></svelte:head>

{#if !vm.loaded}
	<Page title={tg('st.title')} back={{ href: '/struggle', label: tg('st.title') }} width="measure" hideTitle>
		<Skeleton shape="block" height="6rem" />
	</Page>
{:else if !vm.word}
	<Page title={tg('st.notFoundTitle')} back={{ href: '/struggle', label: tg('st.title') }} width="measure">
		<EmptyState size="page" art="success" title={tg('st.notFoundTitle')} body={tg('st.notFound')} />
	</Page>
{:else}
	{@const w = vm.word}
	<Page title={title} titleLang="ja" lead={w.entry ? `${w.entry.headwordReading} · ${w.entry.glosses.slice(0, 3).join('; ')}` : undefined} back={{ href: '/struggle', label: tg('st.title') }} width="measure">
		{#snippet actions()}
			<Button variant="primary" icon="review" href="/review?drill=hot_words">{tg('st.extraDrill')}</Button>
			{#if w.entrySeq !== null}<Button variant="outline" icon="dictionary" href={dictionaryHref(w.entrySeq)}>{tg('st.openDictionary')}</Button>{/if}
		{/snippet}

		<dl class="figs">
			<div><dt>{tg('st.fails14')}</dt><dd class="kb-num bad">{w.failures14d}</dd></div>
			<div><dt>{tg('st.scoreLabel')}</dt><dd class="kb-num">{w.score.toFixed(1)}</dd></div>
			<div><dt>{tg('st.cardsLabel')}</dt><dd class="kb-num">{w.cards.length}</dd></div>
		</dl>

		<div class="stack">
			<Panel title={tg('st.mnemonic')} subtitle={tg('st.mnemonicHint')}>
				<TextArea label={tg('st.mnemonic')} hideLabel bind:value={vm.mnemonic} rows={3} />
				<div class="right"><Button variant="tonal" loading={vm.busy} onclick={() => vm.saveMnemonic()}>{tg('st.mnemonicSave')}</Button></div>
			</Panel>

			<Panel title={tg('st.sentences')} flush>
				{#if vm.sentences.length === 0}
					<p class="none">{tg('st.sentencesNone')}</p>
				{:else}
					<ul class="sents">
						{#each vm.sentences as s (s.id)}
							<li>
								<p lang="ja">{s.text}</p>
								<Button size="sm" variant="quiet" icon="plus" disabled={vm.busy} onclick={() => vm.makeCard(s)}>{tg('st.makeCard')}</Button>
							</li>
						{/each}
					</ul>
				{/if}
			</Panel>

			<Panel title={tg('st.partners')}>
				{#if vm.partners.length === 0}
					<p class="muted">{tg('st.partnersNone')}</p>
				{:else}
					<ul class="partners">
						{#each vm.partners as c (`${c.noteAId}/${c.noteBId}/${c.reason}`)}
							<li>
								<span class="pw" lang="ja">{noteTitle(vm.partner(c))}</span>
								<span class="muted">{tgEnum('st.reason', c.reason)} · {tgEnum('st.status', c.status)}</span>
							</li>
						{/each}
					</ul>
				{/if}
			</Panel>
		</div>
		<p class="note">{tg('st.extraDrillNote')} {tg('st.drillNote')}</p>
	</Page>
{/if}

<style>
	.figs {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 9rem), 1fr));
		gap: var(--kb-space-3);
		margin: 0 0 var(--kb-space-5);
	}
	.figs div {
		padding: var(--kb-space-4);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
	}
	dt {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	dd {
		margin: 0;
		font: var(--kb-type-display);
		font-size: 2rem;
	}
	.bad {
		color: var(--kb-color-grade-again);
	}
	.stack {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
	}
	.right {
		display: flex;
		justify-content: flex-end;
		margin-top: var(--kb-space-3);
	}
	.none {
		padding: 0 var(--kb-space-5) var(--kb-space-3);
		color: var(--kb-color-text-muted);
	}
	.sents {
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.sents li {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: var(--kb-space-2);
		padding: var(--kb-space-3) var(--kb-space-5);
		border-top: 1px solid var(--kb-color-border);
	}
	.sents p {
		flex: 1 1 16rem;
		font: var(--kb-type-jp-body);
	}
	.partners {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.partners li {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: var(--kb-space-3);
	}
	.pw {
		font: var(--kb-type-title);
	}
	.muted {
		color: var(--kb-color-text-muted);
		font: var(--kb-type-body-sm);
	}
	.note {
		margin-top: var(--kb-space-5);
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
</style>
