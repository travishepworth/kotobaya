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

	let summary = $state<StatsSummary | null>(null);
	let heatmap = $state<StatsHeatmap | null>(null);
	let retention = $state<StatsRetention | null>(null);
	let forecast = $state<StatsForecast | null>(null);
	let known = $state<StatsKnownWords | null>(null);
	let calibration = $state<StatsCalibration | null>(null);
	let loaded = $state(false);
	let failed = $state(false);

	$effect(() => {
		void (async () => {
			try {
				[summary, heatmap, retention, forecast, known, calibration] = await Promise.all([
					api.getStatsSummary(),
					api.getStatsHeatmap(),
					api.getStatsRetention({ groupBy: 'card_kind' }),
					api.getStatsForecast({ days: 30 }),
					api.getStatsKnownWords({ interval: 'week' }),
					api.getStatsCalibration()
				]);
			} catch {
				failed = true; // the client already toasted
			} finally {
				loaded = true;
			}
		})();
	});

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

<h1>{ts('stats.title')}</h1>

{#if !loaded}
	<p>{ts('stats.loading')}</p>
{:else if failed}
	<p>{ts('stats.error')}</p>
{:else}
	{#if summary}
		<section>
			<h2>{ts('stats.summary')}</h2>
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
		</section>
	{/if}

	{#if heatmap}
		<section>
			<h2>{ts('stats.heatmap')}</h2>
			{#if heatmap.days.length === 0}
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
	{/if}

	{#if retention}
		<section>
			<h2>{ts('stats.retention')}</h2>
			{#if retention.groups.length === 0}
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
	{/if}

	{#if forecast}
		<section>
			<h2>{ts('stats.forecast')}</h2>
			<svg viewBox={`0 0 ${forecast.days.length * 10} 60`} width="100%" role="img" aria-label={ts('stats.forecast')}>
				{#each forecast.days as d, i (iso(d.date))}
					{@const h = (d.due / forecastMax) * 58}
					<rect x={i * 10 + 1} y={60 - h} width="8" height={h} fill="var(--kb-link)">
						<title>{ts('stats.forecastBar', { date: iso(d.date), n: d.due })}</title>
					</rect>
				{/each}
			</svg>
		</section>
	{/if}

	{#if known}
		<section>
			<h2>{ts('stats.knownWords')}</h2>
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
		</section>
	{/if}

	{#if calibration}
		<section>
			<h2>{ts('stats.calibration')}</h2>
			<p><small>{ts('stats.calibrationHelp')}</small></p>
			{#if calibration.bins.length === 0}
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
	{/if}
{/if}

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
