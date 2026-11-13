<script lang="ts">
	import { StatsApi } from '../../lib/api/generated';
	import type {
		StatsCalibration,
		StatsForecast,
		StatsHeatmap,
		StatsKnownWords,
		StatsRetention,
		StatsSummary
	} from '../../lib/api/generated';
	import { apiConfig } from '../../lib/api/client';
	import { ts } from './messages';

	const api = new StatsApi(apiConfig);

	// Each panel loads on its own: one slow or failing endpoint never blanks the others.
	type Panel<T> = { data: T | null; failed: boolean };

	let summaryP = $state<Panel<StatsSummary>>({ data: null, failed: false });
	let heatmapP = $state<Panel<StatsHeatmap>>({ data: null, failed: false });
	let retentionP = $state<Panel<StatsRetention>>({ data: null, failed: false });
	let forecastP = $state<Panel<StatsForecast>>({ data: null, failed: false });
	let knownP = $state<Panel<StatsKnownWords>>({ data: null, failed: false });
	let calibrationP = $state<Panel<StatsCalibration>>({ data: null, failed: false });

	function load<T>(fetch: () => Promise<T>, set: (p: Panel<T>) => void): void {
		fetch().then(
			(data) => set({ data, failed: false }),
			() => set({ data: null, failed: true }) // the client already toasted
		);
	}

	$effect(() => {
		load(() => api.getStatsSummary(), (p) => (summaryP = p));
		load(() => api.getStatsHeatmap(), (p) => (heatmapP = p));
		load(() => api.getStatsRetention({ groupBy: 'card_kind' }), (p) => (retentionP = p));
		load(() => api.getStatsForecast({ days: 30 }), (p) => (forecastP = p));
		load(() => api.getStatsKnownWords({ interval: 'week' }), (p) => (knownP = p));
		load(() => api.getStatsCalibration(), (p) => (calibrationP = p));
	});

	const summary = $derived(summaryP.data);
	const heatmap = $derived(heatmapP.data);
	const retention = $derived(retentionP.data);
	const forecast = $derived(forecastP.data);
	const known = $derived(knownP.data);
	const calibration = $derived(calibrationP.data);

	const pct = (v: number | null): string => (v === null ? '-' : `${(v * 100).toFixed(1)}%`);
	const iso = (d: Date): string => d.toISOString().slice(0, 10);

	// Heatmap: one cell per day, weeks as columns (Monday first).
	const cells = $derived.by(() => {
		if (!heatmap) return [] as { x: number; y: number; date: string; n: number }[];
		const byDate = new Map(heatmap.days.map((d) => [iso(d.date), d.reviews]));
		const start = new Date(heatmap.from.getTime());
		start.setUTCDate(start.getUTCDate() - ((start.getUTCDay() + 6) % 7));
		const out: { x: number; y: number; date: string; n: number }[] = [];
		for (let t = start.getTime(), i = 0; t <= heatmap.to.getTime(); t += 86_400_000, i++) {
			const date = iso(new Date(t));
			out.push({ x: Math.floor(i / 7), y: i % 7, date, n: byDate.get(date) ?? 0 });
		}
		return out;
	});
	const heatMax = $derived(Math.max(1, ...cells.map((c) => c.n)));
	const heatCols = $derived(cells.length ? cells[cells.length - 1].x + 1 : 0);

	const forecastMax = $derived(Math.max(1, ...(forecast?.days.map((d) => d.due) ?? [0])));
	const knownMax = $derived(Math.max(1, ...(known?.series.map((p) => p.known) ?? [0])));
	const knownNow = $derived(known ? known.current.known + known.current.neverForget : 0);
</script>

{#snippet pending(failed: boolean)}
	{#if failed}
		<p role="alert">{ts('stats.panelError')}</p>
	{:else}
		<p aria-busy="true">{ts('stats.loading')}</p>
	{/if}
{/snippet}

<h1>{ts('stats.title')}</h1>

<section>
	<h2>{ts('stats.summary')}</h2>
	{#if summary}
		<p>{ts('stats.due', { new: summary.due._new, learning: summary.due.learning, review: summary.due.review })}</p>
		<p>
			{ts('stats.done', {
				n: summary.doneToday.reviews,
				m: summary.doneToday.newCards,
				min: Math.round(summary.doneToday.timeMs / 60000)
			})}
		</p>
		<p>{ts('stats.retentionToday', { pct: pct(summary.doneToday.retention) })}</p>
		<p>{ts('stats.streak', { n: summary.streakDays })}</p>
		<p>
			{ts('stats.totals', {
				notes: summary.totals.notes,
				cards: summary.totals.cards,
				suspended: summary.totals.suspended,
				leeches: summary.totals.leeches
			})}
		</p>
	{:else}
		{@render pending(summaryP.failed)}
	{/if}
</section>

<section>
	<h2>{ts('stats.heatmap')}</h2>
	{#if !heatmap}
		{@render pending(heatmapP.failed)}
	{:else if heatmap.days.length === 0}
		<p>{ts('stats.none')}</p>
	{:else}
		<svg viewBox={`0 0 ${heatCols * 12} 84`} width="100%" role="img" aria-label={ts('stats.heatmap')}>
			{#each cells as c (c.date)}
				<rect x={c.x * 12} y={c.y * 12} width="10" height="10" rx="2" fill="var(--kb-accent)" fill-opacity={c.n === 0 ? 0.06 : 0.2 + 0.8 * (c.n / heatMax)}>
					<title>{ts('stats.heatmapDay', { date: c.date, n: c.n })}</title>
				</rect>
			{/each}
		</svg>
	{/if}
</section>

<section>
	<h2>{ts('stats.retention')}</h2>
	{#if !retention}
		{@render pending(retentionP.failed)}
	{:else if retention.groups.length === 0}
		<p>{ts('stats.none')}</p>
	{:else}
		<table>
			<tbody>
				{#each [retention.overall, ...retention.groups] as g (g.key)}
					<tr>
						<th scope="row">{g.key === 'all' ? ts('stats.overall') : g.key}</th>
						<td>
							<div class="bar" style:width={`${(g.retention ?? 0) * 100}%`}></div>
						</td>
						<td>{ts('stats.retentionRow', { pct: pct(g.retention), n: g.total })}</td>
						<td>
							<small>
								{ts('stats.retentionYoungMature', {
									young: pct(g.youngTotal ? g.youngPassed / g.youngTotal : null),
									mature: pct(g.matureTotal ? g.maturePassed / g.matureTotal : null)
								})}
							</small>
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
	{/if}
</section>

<section>
	<h2>{ts('stats.forecast')}</h2>
	{#if forecast}
		<svg viewBox={`0 0 ${forecast.days.length * 10} 60`} width="100%" role="img" aria-label={ts('stats.forecast')}>
			{#each forecast.days as d, i (iso(d.date))}
				{@const h = (d.due / forecastMax) * 58}
				<rect x={i * 10 + 1} y={60 - h} width="8" height={h} fill="var(--kb-link)">
					<title>{ts('stats.forecastBar', { date: iso(d.date), n: d.due })}</title>
				</rect>
			{/each}
		</svg>
	{:else}
		{@render pending(forecastP.failed)}
	{/if}
</section>

<section>
	<h2>{ts('stats.knownWords')}</h2>
	{#if known}
		<p>{ts('stats.knownNow', { n: knownNow })}</p>
		{#if known.series.length > 1}
			{@const n = known.series.length}
			<svg viewBox="0 0 200 60" width="100%" role="img" aria-label={ts('stats.knownWords')}>
				<polyline
					fill="none"
					stroke="var(--kb-accent)"
					stroke-width="1.5"
					points={known.series.map((p, i) => `${(i / (n - 1)) * 200},${58 - (p.known / knownMax) * 56}`).join(' ')}
				/>
				{#each known.series as p, i (iso(p.date))}
					<circle cx={(i / (n - 1)) * 200} cy={58 - (p.known / knownMax) * 56} r="1.2" fill="var(--kb-accent)">
						<title>{ts('stats.knownPoint', { date: iso(p.date), n: p.known })}</title>
					</circle>
				{/each}
			</svg>
		{/if}
	{:else}
		{@render pending(knownP.failed)}
	{/if}
</section>

<section>
	<h2>{ts('stats.calibration')}</h2>
	<p><small>{ts('stats.calibrationHelp')}</small></p>
	{#if !calibration}
		{@render pending(calibrationP.failed)}
	{:else if calibration.bins.length === 0}
		<p>{ts('stats.none')}</p>
	{:else}
		<svg viewBox="-8 -4 116 116" width="240" role="img" aria-label={ts('stats.calibration')}>
			<rect x="0" y="0" width="100" height="100" fill="none" stroke="var(--kb-border)" />
			<line x1="0" y1="100" x2="100" y2="0" stroke="var(--kb-border)" stroke-dasharray="3" />
			{#each calibration.bins as b (b.lower)}
				<circle cx={b.predicted * 100} cy={100 - b.actual * 100} r={2 + Math.min(4, Math.log10(b.count + 1))} fill="var(--kb-accent)" fill-opacity="0.8">
					<title>{ts('stats.calibrationPoint', { p: pct(b.predicted), a: pct(b.actual), n: b.count })}</title>
				</circle>
			{/each}
		</svg>
	{/if}
</section>

<style>
	section {
		margin-block: var(--kb-space-6);
	}
	table {
		border-collapse: collapse;
		width: 100%;
	}
	th,
	td {
		text-align: left;
		padding: var(--kb-space-1) var(--kb-space-2);
	}
	td:nth-child(2) {
		width: 30%;
	}
	.bar {
		height: 0.6rem;
		background: var(--kb-accent);
		border-radius: 2px;
	}
</style>
