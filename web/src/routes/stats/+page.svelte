<script lang="ts">
	import { iso, pct, StatsPage } from '../../lib/state/stats.svelte';
	import { ts } from './messages';

	const vm = new StatsPage();

	$effect(() => {
		vm.start();
	});
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
	{#if vm.summary}
		<p>{ts('stats.due', { new: vm.summary.due._new, learning: vm.summary.due.learning, review: vm.summary.due.review })}</p>
		<p>
			{ts('stats.done', {
				n: vm.summary.doneToday.reviews,
				m: vm.summary.doneToday.newCards,
				min: Math.round(vm.summary.doneToday.timeMs / 60000)
			})}
		</p>
		<p>{ts('stats.retentionToday', { pct: pct(vm.summary.doneToday.retention) })}</p>
		<p>{ts('stats.streak', { n: vm.summary.streakDays })}</p>
		<p>
			{ts('stats.totals', {
				notes: vm.summary.totals.notes,
				cards: vm.summary.totals.cards,
				suspended: vm.summary.totals.suspended,
				leeches: vm.summary.totals.leeches
			})}
		</p>
	{:else}
		{@render pending(vm.summaryP.failed)}
	{/if}
</section>

<section>
	<h2>{ts('stats.heatmap')}</h2>
	{#if !vm.heatmap}
		{@render pending(vm.heatmapP.failed)}
	{:else if vm.heatmap.days.length === 0}
		<p>{ts('stats.none')}</p>
	{:else}
		<svg viewBox={`0 0 ${vm.heatCols * 12} 84`} width="100%" role="img" aria-label={ts('stats.heatmap')}>
			{#each vm.cells as c (c.date)}
				<rect x={c.x * 12} y={c.y * 12} width="10" height="10" rx="2" fill="var(--kb-accent)" fill-opacity={c.n === 0 ? 0.06 : 0.2 + 0.8 * (c.n / vm.heatMax)}>
					<title>{ts('stats.heatmapDay', { date: c.date, n: c.n })}</title>
				</rect>
			{/each}
		</svg>
	{/if}
</section>

<section>
	<h2>{ts('stats.retention')}</h2>
	{#if !vm.retention}
		{@render pending(vm.retentionP.failed)}
	{:else if vm.retention.groups.length === 0}
		<p>{ts('stats.none')}</p>
	{:else}
		<table>
			<tbody>
				{#each [vm.retention.overall, ...vm.retention.groups] as g (g.key)}
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
	{#if vm.forecast}
		<svg viewBox={`0 0 ${vm.forecast.days.length * 10} 60`} width="100%" role="img" aria-label={ts('stats.forecast')}>
			{#each vm.forecast.days as d, i (iso(d.date))}
				{@const h = (d.due / vm.forecastMax) * 58}
				<rect x={i * 10 + 1} y={60 - h} width="8" height={h} fill="var(--kb-link)">
					<title>{ts('stats.forecastBar', { date: iso(d.date), n: d.due })}</title>
				</rect>
			{/each}
		</svg>
	{:else}
		{@render pending(vm.forecastP.failed)}
	{/if}
</section>

<section>
	<h2>{ts('stats.knownWords')}</h2>
	{#if vm.known}
		<p>{ts('stats.knownNow', { n: vm.knownNow })}</p>
		{#if vm.known.series.length > 1}
			{@const n = vm.known.series.length}
			<svg viewBox="0 0 200 60" width="100%" role="img" aria-label={ts('stats.knownWords')}>
				<polyline
					fill="none"
					stroke="var(--kb-accent)"
					stroke-width="1.5"
					points={vm.known.series.map((p, i) => `${(i / (n - 1)) * 200},${58 - (p.known / vm.knownMax) * 56}`).join(' ')}
				/>
				{#each vm.known.series as p, i (iso(p.date))}
					<circle cx={(i / (n - 1)) * 200} cy={58 - (p.known / vm.knownMax) * 56} r="1.2" fill="var(--kb-accent)">
						<title>{ts('stats.knownPoint', { date: iso(p.date), n: p.known })}</title>
					</circle>
				{/each}
			</svg>
		{/if}
	{:else}
		{@render pending(vm.knownP.failed)}
	{/if}
</section>

<section>
	<h2>{ts('stats.calibration')}</h2>
	<p><small>{ts('stats.calibrationHelp')}</small></p>
	{#if !vm.calibration}
		{@render pending(vm.calibrationP.failed)}
	{:else if vm.calibration.bins.length === 0}
		<p>{ts('stats.none')}</p>
	{:else}
		<svg viewBox="-8 -4 116 116" width="240" role="img" aria-label={ts('stats.calibration')}>
			<rect x="0" y="0" width="100" height="100" fill="none" stroke="var(--kb-border)" />
			<line x1="0" y1="100" x2="100" y2="0" stroke="var(--kb-border)" stroke-dasharray="3" />
			{#each vm.calibration.bins as b (b.lower)}
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
