<script lang="ts">
	// Coverage: how much of this work you already know (bar with labelled segments), what learning more would give,
	// per-part numbers, and the unknown words to learn next.
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import Banner from '../../../lib/ui/Banner.svelte';
	import Button from '../../../lib/ui/Button.svelte';
	import Chip from '../../../lib/ui/Chip.svelte';
	import EmptyState from '../../../lib/ui/EmptyState.svelte';
	import LineChart from '../../../lib/ui/LineChart.svelte';
	import Page from '../../../lib/ui/Page.svelte';
	import Panel from '../../../lib/ui/Panel.svelte';
	import Select from '../../../lib/ui/Select.svelte';
	import Skeleton from '../../../lib/ui/Skeleton.svelte';
	import { tc, type CoverageMessageKey } from '../../../lib/coverage/messages';
	import { tm, type MediaMessageKey } from '../../../lib/media/messages';
	import { HIDE_CLASSES, MediaCoveragePage } from '../../../lib/state/media.svelte';
	import { mx } from '../messages';
	import UnknownWords from './UnknownWords.svelte';

	const id = $derived(page.params.id ?? '');
	const vm = new MediaCoveragePage(() => id);
	onMount(() => vm.mount());

	const segOptions = $derived([
		{ value: '', label: tc('cov.segment.all') },
		...vm.segments.map((s) => ({ value: s.id, label: s.title || String(s.ordinal + 1) }))
	]);
	const pct = (n: number, d: number): number => (d > 0 ? (n / d) * 100 : 0);
</script>

<svelte:head><title>{vm.media?.title ?? tc('cov.tab.coverage')} · Kotobaya</title></svelte:head>

{#if vm.missing}
	<Page title={tc('cov.notFound')} width="measure" hideTitle>
		<EmptyState size="page" art="media" title={tc('cov.notFound')}>
			{#snippet action()}<Button variant="primary" href="/media">{tc('cov.back')}</Button>{/snippet}
		</EmptyState>
	</Page>
{:else if !vm.media}
	<Page title={tc('cov.tab.coverage')} hideTitle><Skeleton shape="block" height="14rem" /></Page>
{:else}
	{@const m = vm.media}
	<Page
		title={m.title}
		titleLang="ja"
		lead={tc('cov.meta', { kind: tm(`med.kind.${m.kind}` as MediaMessageKey), status: tm(`med.parse.${m.parseStatus}` as MediaMessageKey), segments: vm.segments.length })}
	>
		{#if m.contentVersion === 0}<div class="gap"><Banner tone="warning">{tc('cov.notParsed')}</Banner></div>{/if}

		<div class="filters">
			{#if vm.segments.length > 0}
				<div class="seg"><Select label={tc('cov.segment')} hideLabel options={segOptions} value={vm.filters.segmentId ?? ''} onchange={(v) => vm.pickSegment(v)} /></div>
			{/if}
			<div class="hide" role="group" aria-label={tc('cov.hide')}>
				<span class="hl">{tc('cov.hide')}</span>
				{#each HIDE_CLASSES as c (c)}
					<Chip kind="filter" label={tc(`cov.hide.${c}` as CoverageMessageKey)} selected={vm.filters.hide.includes(c)} onclick={() => vm.toggle(c, !vm.filters.hide.includes(c))} />
				{/each}
			</div>
		</div>

		{#if !vm.coverage}
			<Skeleton shape="block" height="12rem" />
		{:else}
			{@const c = vm.coverage}
			<div class="grid">
				<Panel>
					<div class="headline">
						<p class="big kb-num">{c.tokens.knownPct}%</p>
						<p class="muted">{tc('cov.tokenCoverage')} · {mx('mx.tokens', { n: c.tokens.total.toLocaleString() })}</p>
					</div>
					<div class="bar" aria-hidden="true">
						<span class="known" style:width="{pct(c.tokens.known, c.tokens.total)}%"></span>
						<span class="learning" style:width="{pct(c.tokens.learning, c.tokens.total)}%"></span>
						<span class="unknown" style:width="{pct(c.tokens.unknown, c.tokens.total)}%"></span>
					</div>
					<ul class="legend">
						<li><i class="known"></i>{mx('mx.known')} <b class="kb-num">{c.tokens.known.toLocaleString()}</b></li>
						<li><i class="learning"></i>{mx('mx.learning')} <b class="kb-num">{c.tokens.learning.toLocaleString()}</b></li>
						<li><i class="unknown"></i>{mx('mx.unknown')} <b class="kb-num">{c.tokens.unknown.toLocaleString()}</b></li>
						<li><i class="unmapped"></i>{mx('mx.unmapped')} <b class="kb-num">{c.tokens.unmapped.toLocaleString()}</b></li>
					</ul>
					<dl class="facts">
						<div><dt>{mx('mx.uniqueTitle')}</dt><dd class="kb-num">{c.unique.known.toLocaleString()} / {c.unique.total.toLocaleString()}</dd></div>
						<div><dt>{tc('cov.ignored')}</dt><dd class="kb-num">{c.ignoredCount.toLocaleString()}</dd></div>
						{#if c.difficulty}
							<div><dt>{tc('cov.difficulty')}</dt><dd>{tc('cov.difficultyRank', { rank: c.difficulty.rank, of: c.difficulty.of })}</dd></div>
						{/if}
					</dl>
				</Panel>

				<Panel title={mx('mx.curveTitle')} subtitle={mx('mx.curveSub')}>
					{#if c.curve.length > 1}
						<LineChart
							caption={tc('cov.curve')}
							points={c.curve.map((p) => ({ key: String(p.words), label: tc('cov.curveLabel', { words: p.words, pct: p.knownPct }), short: mx('mx.curveWords', { n: p.words }), value: p.knownPct }))}
							max={100}
							format={(n) => `${Math.round(n)}%`}
							height="9rem"
						/>
					{:else}
						<p class="muted">{tc('cov.notParsed')}</p>
					{/if}
				</Panel>
			</div>

			{#if c.segments.length > 0}
				<div class="gap">
					<Panel title={tc('cov.segments')} flush>
						<ul class="segs">
							{#each c.segments as s (s.segmentId)}
								<li>
									<span class="st" lang="ja">{s.title || s.ordinal + 1}</span>
									<span class="sbar" aria-hidden="true"><i style:width="{s.knownPct}%"></i></span>
									<span class="sp kb-num">{s.knownPct}%</span>
									<span class="su kb-num">{tc('cov.col.unknown')} {s.uniqueUnknown.toLocaleString()}</span>
								</li>
							{/each}
						</ul>
					</Panel>
				</div>
			{/if}
		{/if}

		<!-- The media's deck is named by the coverage answer (the old screen passed the media id and got 404s). -->
		{#if vm.coverage}
			<div class="gap"><UnknownWords deckId={vm.coverage.deckId} onchanged={() => vm.reloadCoverage()} /></div>
		{/if}
	</Page>
{/if}

<style>
	.gap {
		margin-top: var(--kb-space-5);
	}
	.filters {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-3);
		margin-bottom: var(--kb-space-5);
	}
	.seg {
		flex: 0 1 16rem;
		min-width: 0;
	}
	.hide {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2);
	}
	.hl {
		font: var(--kb-type-label);
		color: var(--kb-color-text-muted);
	}
	.grid {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: var(--kb-space-5);
	}
	@media (min-width: 1024px) {
		.grid {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}
	.headline {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: var(--kb-space-1) var(--kb-space-3);
	}
	.big {
		font: var(--kb-type-display);
		font-size: 3rem;
		line-height: 1;
	}
	.muted {
		color: var(--kb-color-text-muted);
		font: var(--kb-type-body-sm);
	}
	.bar {
		display: flex;
		height: 12px;
		margin: var(--kb-space-4) 0 var(--kb-space-3);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		overflow: hidden;
	}
	.bar span {
		height: 100%;
	}
	.known {
		background: var(--kb-color-state-review);
	}
	.learning {
		background: var(--kb-color-state-learning);
	}
	.unknown {
		background: var(--kb-color-state-new);
	}
	.unmapped {
		background: var(--kb-color-border-strong);
	}
	.legend {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1) var(--kb-space-4);
		margin: 0;
		padding: 0;
		list-style: none;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.legend li {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
	}
	.legend b {
		color: var(--kb-color-text);
		font-weight: 600;
	}
	.legend i {
		width: 0.625rem;
		height: 0.625rem;
		border-radius: 2px;
	}
	.facts {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 9rem), 1fr));
		gap: var(--kb-space-3);
		margin: var(--kb-space-5) 0 0;
	}
	dt {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	dd {
		margin: 0;
		font: var(--kb-type-title-sm);
	}
	.segs {
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.segs li {
		display: grid;
		grid-template-columns: minmax(6rem, 1.4fr) minmax(4rem, 2fr) 3.5rem minmax(6rem, auto);
		align-items: center;
		gap: var(--kb-space-3);
		min-height: 3rem;
		padding: var(--kb-space-2) var(--kb-space-5);
		border-top: 1px solid var(--kb-color-border);
	}
	@container (max-width: 32rem) {
		.segs li {
			grid-template-columns: minmax(0, 1fr) 3.5rem;
		}
		.sbar,
		.su {
			display: none;
		}
	}
	.st {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.sbar {
		height: 6px;
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		overflow: hidden;
	}
	.sbar i {
		display: block;
		height: 100%;
		background: var(--kb-color-state-review);
	}
	.sp {
		text-align: end;
		font-weight: 600;
	}
	.su {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
</style>
