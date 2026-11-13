<script lang="ts">
	// Stats: today's figures, then charts that read in both themes (muted axes, hairline grids, labelled legends).
	// Every panel loads on its own; one failing never blanks the others.
	import BarChart from '../../lib/ui/BarChart.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import LineChart from '../../lib/ui/LineChart.svelte';
	import Page from '../../lib/ui/Page.svelte';
	import Panel from '../../lib/ui/Panel.svelte';
	import SegmentedControl from '../../lib/ui/SegmentedControl.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import { ut } from '../../lib/ui/messages';
	import { iso, StatsPage } from '../../lib/state/stats.svelte';
	import { ts, type StatsMessageKey } from './messages';

	const vm = new StatsPage();
	$effect(() => {
		vm.start();
	});

	type Range = 'week' | 'month' | 'year';
	let range = $state<Range>('month');

	const pct = (v: number | null | undefined): string => (v == null ? '–' : `${Math.round(v * 100)}%`);
	const fmtDay = (d: Date): string => d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', timeZone: 'UTC' });
	const css = (name: string): string => `var(--kb-color-${name})`;

	const empty = $derived(!!vm.summary && vm.summary.totals.cards === 0 && vm.summary.doneToday.reviews === 0);

	// Reviews per day for the chosen range (the year is shown per week so the bars stay readable).
	const reviewBars = $derived.by(() => {
		const days = vm.heatmap?.days ?? [];
		const n = range === 'week' ? 7 : range === 'month' ? 30 : 364;
		const slice = days.slice(-n);
		const split = (d: (typeof days)[number]): number[] => [d.again, d.newCards, Math.max(0, d.reviews - d.again - d.newCards)];
		if (range !== 'year') {
			return slice.map((d, i) => ({
				key: iso(d.date),
				label: fmtDay(d.date),
				short: range === 'week' ? d.date.toLocaleDateString(undefined, { weekday: 'short', timeZone: 'UTC' }) : i % 7 === 0 ? fmtDay(d.date) : '',
				values: split(d)
			}));
		}
		const weeks = [];
		for (let i = 0; i < slice.length; i += 7) {
			const w = slice.slice(i, i + 7);
			const sum = w.map(split).reduce((a, b) => a.map((x, j) => x + b[j]), [0, 0, 0]);
			weeks.push({
				key: iso(w[0].date),
				label: fmtDay(w[0].date),
				short: w[0].date.toLocaleDateString(undefined, { month: 'short', timeZone: 'UTC' }),
				values: sum
			});
		}
		return weeks;
	});
	const reviewTotal = $derived(reviewBars.reduce((a, b) => a + b.values.reduce((x, y) => x + y, 0), 0));
	const series = [
		{ label: ts('stats.series.again'), color: css('grade-again') },
		{ label: ts('stats.series.new'), color: css('state-new') },
		{ label: ts('stats.series.review'), color: css('state-review') }
	];

	const forecastBars = $derived(
		(vm.forecast?.days ?? []).map((d, i) => ({
			key: iso(d.date),
			label: fmtDay(d.date),
			short: i % 7 === 0 ? fmtDay(d.date) : '',
			values: [d.due]
		}))
	);

	const knownPoints = $derived(
		(vm.known?.series ?? []).map((p) => ({ key: iso(p.date), label: fmtDay(p.date), short: fmtDay(p.date), value: p.known }))
	);

	function kindName(k: string): string {
		const key = `stats.kind.${k}` as StatsMessageKey;
		return k === 'meaning' || k === 'reading' ? ts(key) : k;
	}
</script>

<svelte:head><title>{ts('stats.title')} · Kotobaya</title></svelte:head>

{#snippet failed()}
	<EmptyState tone="error" art="alert" title={ts('stats.panelError')}>
		{#snippet action()}<Button variant="outline" size="sm" onclick={() => vm.start()}>{ut('app.retry')}</Button>{/snippet}
	</EmptyState>
{/snippet}

{#snippet pending(p: { failed: boolean }, h: string)}
	{#if p.failed}{@render failed()}{:else}<Skeleton shape="block" height={h} />{/if}
{/snippet}

<Page title={ts('stats.title')}>
	{#if empty}
		<EmptyState size="page" art="stats" title={ts('stats.emptyTitle')} body={ts('stats.emptyBody')}>
			{#snippet action()}<Button variant="primary" href="/review">{ts('stats.startReview')}</Button>{/snippet}
		</EmptyState>
	{:else}
		<section class="figures" aria-label={ts('stats.summary')}>
			{#each [['stats.fig.reviews', vm.summary ? vm.summary.doneToday.reviews.toLocaleString() : null], ['stats.fig.retention', vm.summary ? pct(vm.summary.doneToday.retention) : null], ['stats.fig.time', vm.summary ? ts('stats.fig.min', { n: Math.round(vm.summary.doneToday.timeMs / 60000) }) : null], ['stats.fig.streak', vm.summary ? String(vm.summary.streakDays) : null]] as [k, v] (k)}
				<div class="figure">
					{#if v === null}
						{#if vm.summaryP.failed}<p class="fv">–</p>{:else}<Skeleton shape="block" height="2.75rem" />{/if}
					{:else}
						<p class="fv kb-num">{v}</p>
					{/if}
					<p class="fl">{ts(k as StatsMessageKey)}</p>
				</div>
			{/each}
		</section>

		<div class="grid">
			<div class="wide">
				<Panel title={range === 'year' ? ts('stats.reviewsWeekly') : ts('stats.reviewsChart')} subtitle={vm.heatmap ? ts('stats.reviewsTotal', { n: reviewTotal.toLocaleString() }) : undefined}>
					{#snippet action()}
						<SegmentedControl
							label={ts('stats.range')}
							bind:value={range}
							options={[
								{ value: 'week', label: ts('stats.range.week') },
								{ value: 'month', label: ts('stats.range.month') },
								{ value: 'year', label: ts('stats.range.year') }
							]}
						/>
					{/snippet}
					{#if vm.heatmap}
						<BarChart caption={ts('stats.reviewsChart')} bars={reviewBars} {series} height="12rem" />
					{:else}
						{@render pending(vm.heatmapP, '15rem')}
					{/if}
				</Panel>
			</div>

			<Panel title={ts('stats.forecast')} subtitle={ts('stats.forecastSub')}>
				{#if vm.forecast}
					<BarChart caption={ts('stats.forecast')} bars={forecastBars} series={[{ label: ts('stats.forecast'), color: css('primary') }]} current={forecastBars[0]?.key} />
				{:else}
					{@render pending(vm.forecastP, '12rem')}
				{/if}
			</Panel>

			<Panel title={ts('stats.knownWords')} subtitle={vm.known ? ts('stats.knownSub', { n: vm.knownNow.toLocaleString() }) : undefined}>
				{#if vm.known && knownPoints.length > 1}
					<LineChart caption={ts('stats.knownWords')} points={knownPoints} min={0} height="10rem" />
				{:else if vm.known}
					<p class="muted">{ts('stats.none')}</p>
				{:else}
					{@render pending(vm.knownP, '12rem')}
				{/if}
			</Panel>

			<Panel title={ts('stats.retention')} subtitle={ts('stats.retentionSub')}>
				{#if vm.retention}
					{@const groups = [vm.retention.overall, ...vm.retention.groups]}
					<ul class="meters">
						{#each groups as g (g.key)}
							<li>
								<div class="meter-head">
									<span class="mk">{g.key === 'overall' ? ts('stats.overall') : kindName(g.key)}</span>
									<span class="mv kb-num">{pct(g.retention)}</span>
								</div>
								<div class="track" aria-hidden="true"><div class="fill" class:overall={g.key === 'overall'} style:width="{(g.retention ?? 0) * 100}%"></div></div>
								<p class="ms kb-num">
									{ts('stats.reviewsN', { n: g.total.toLocaleString() })} · {ts('stats.young')}
									{pct(g.youngTotal ? g.youngPassed / g.youngTotal : null)} · {ts('stats.mature')}
									{pct(g.matureTotal ? g.maturePassed / g.matureTotal : null)}
								</p>
							</li>
						{/each}
					</ul>
				{:else}
					{@render pending(vm.retentionP, '12rem')}
				{/if}
			</Panel>

			<Panel title={ts('stats.calibration')} subtitle={ts('stats.calibrationSub')}>
				{#if vm.calibration && vm.calibration.bins.length > 0}
					<ul class="calib">
						{#each vm.calibration.bins as b (b.lower)}
							<li>
								<span class="range kb-num">{Math.round(b.lower * 100)}–{Math.round(b.upper * 100)}%</span>
								<span class="bars" aria-label="{ts('stats.predicted')} {pct(b.predicted)}, {ts('stats.actual')} {pct(b.actual)}">
									<span class="bar p" style:width="{b.predicted * 100}%"></span>
									<span class="bar a" style:width="{b.actual * 100}%"></span>
								</span>
								<span class="kb-num val">{pct(b.actual)}</span>
							</li>
						{/each}
					</ul>
					<ul class="legend">
						<li><i class="p"></i>{ts('stats.predicted')}</li>
						<li><i class="a"></i>{ts('stats.actual')}</li>
					</ul>
				{:else if vm.calibration}
					<p class="muted">{ts('stats.none')}</p>
				{:else}
					{@render pending(vm.calibrationP, '12rem')}
				{/if}
			</Panel>

			{#if vm.summary}
				<Panel title={ts('stats.totalsTitle')}>
					<dl class="totals">
						<div><dt>{ts('stats.tot.notes')}</dt><dd class="kb-num">{vm.summary.totals.notes.toLocaleString()}</dd></div>
						<div><dt>{ts('stats.tot.cards')}</dt><dd class="kb-num">{vm.summary.totals.cards.toLocaleString()}</dd></div>
						<div><dt>{ts('stats.tot.suspended')}</dt><dd class="kb-num">{vm.summary.totals.suspended.toLocaleString()}</dd></div>
						<div><dt>{ts('stats.tot.leeches')}</dt><dd class="kb-num">{vm.summary.totals.leeches.toLocaleString()}</dd></div>
					</dl>
				</Panel>
			{/if}
		</div>
	{/if}
</Page>

<style>
	.figures {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 10rem), 1fr));
		gap: var(--kb-space-3);
		margin-bottom: var(--kb-space-5);
	}
	.figure {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		padding: var(--kb-space-4) var(--kb-space-5);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
	}
	.fv {
		font: var(--kb-type-display);
		line-height: 1.1;
	}
	.fl {
		font: var(--kb-type-body-sm);
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
		.wide {
			grid-column: 1 / -1;
		}
	}
	.muted {
		color: var(--kb-color-text-muted);
	}
	.meters {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.meter-head {
		display: flex;
		justify-content: space-between;
		gap: var(--kb-space-2);
	}
	.mk {
		font: var(--kb-type-label);
	}
	.mv {
		font: var(--kb-type-title-sm);
		font-weight: 600;
	}
	.track {
		height: 8px;
		margin: var(--kb-space-1) 0;
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		overflow: hidden;
	}
	.fill {
		height: 100%;
		border-radius: inherit;
		background: var(--kb-color-state-review);
	}
	.fill.overall {
		background: var(--kb-color-primary);
	}
	.ms {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.calib {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.calib li {
		display: grid;
		grid-template-columns: auto minmax(0, 1fr) auto;
		align-items: center;
		gap: var(--kb-space-3);
	}
	.range,
	.val {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.val {
		text-align: end;
		color: var(--kb-color-text);
	}
	.bars {
		display: flex;
		flex-direction: column;
		gap: 3px;
	}
	.bar {
		height: 6px;
		border-radius: var(--kb-radius-full);
	}
	.bar.p,
	.legend i.p {
		background: var(--kb-color-border-strong);
	}
	.bar.a,
	.legend i.a {
		background: var(--kb-color-primary);
	}
	.legend {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1) var(--kb-space-4);
		margin: var(--kb-space-4) 0 0;
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
		width: 0.75rem;
		height: 0.375rem;
		border-radius: var(--kb-radius-full);
	}
	.totals {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(7rem, 1fr));
		gap: var(--kb-space-4);
		margin: 0;
	}
	.totals dt {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.totals dd {
		margin: 0;
		font: var(--kb-type-title);
		font-weight: 600;
	}
</style>
