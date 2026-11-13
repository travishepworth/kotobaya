<script lang="ts">
	// Vertical bar chart in HTML (crisp at every width and text size): optional stacked series with a labelled
	// legend, a scaled y axis with three gridlines, sparse x labels, and the tallest bar labelled with its value.
	// The data is also given as a visually hidden table for screen readers.
	interface Series {
		label: string;
		/** CSS colour (a token). */
		color: string;
	}
	interface Bar {
		key: string;
		/** Full label (tooltip, table). */
		label: string;
		/** Short axis label; shown every `labelEvery` bars. */
		short?: string;
		/** One value per series. */
		values: number[];
	}
	interface Props {
		caption: string;
		bars: Bar[];
		series: Series[];
		height?: string;
		labelEvery?: number;
		format?: (n: number) => string;
		/** Highlight one bar (e.g. today). */
		current?: string;
	}
	let { caption, bars, series, height = '10rem', labelEvery = 1, format = (n) => n.toLocaleString(), current }: Props = $props();

	const totals = $derived(bars.map((b) => b.values.reduce((a, v) => a + v, 0)));
	const peak = $derived(Math.max(0, ...totals));
	// A "nice" axis maximum: 1, 2 or 5 × 10^k at or above the peak.
	const axisMax = $derived.by(() => {
		if (peak <= 0) return 1;
		const p = 10 ** Math.floor(Math.log10(peak));
		for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= peak) return m * p;
		return 10 * p;
	});
	const peakIndex = $derived(totals.indexOf(peak));
</script>

<figure class="chart" style:--plot-h={height}>
	<div class="plot" aria-hidden="true">
		<div class="grid">
			{#each [1, 0.5, 0] as f (f)}
				<div class="gridline" style:bottom="{f * 100}%"><span>{format(axisMax * f)}</span></div>
			{/each}
		</div>
		<div class="bars" style:--n={bars.length}>
			{#each bars as b, i (b.key)}
				<div class="col" class:current={b.key === current} title="{b.label}: {format(totals[i])}">
					{#if i === peakIndex && peak > 0}<span class="peak" style:bottom="calc({(peak / axisMax) * 100}% + 2px)">{format(peak)}</span>{/if}
					<div class="stack" style:height="{(totals[i] / axisMax) * 100}%">
						{#each b.values as v, si (si)}
							{#if v > 0}<span class="seg" style:flex-grow={v} style:background={series[si]?.color}></span>{/if}
						{/each}
					</div>
				</div>
			{/each}
		</div>
	</div>
	<div class="xlabels" aria-hidden="true" style:--n={bars.length}>
		{#each bars as b, i (b.key)}
			<span>{i % labelEvery === 0 || i === bars.length - 1 ? (b.short ?? '') : ''}</span>
		{/each}
	</div>
	{#if series.length > 1}
		<ul class="legend">
			{#each series as s (s.label)}<li><i style:background={s.color}></i>{s.label}</li>{/each}
		</ul>
	{/if}
	<table class="kb-sr">
		<caption>{caption}</caption>
		<thead><tr><th scope="col"></th>{#each series as s (s.label)}<th scope="col">{s.label}</th>{/each}</tr></thead>
		<tbody>
			{#each bars as b (b.key)}
				<tr><th scope="row">{b.label}</th>{#each b.values as v, si (si)}<td>{format(v)}</td>{/each}</tr>
			{/each}
		</tbody>
	</table>
</figure>

<style>
	.chart {
		margin: 0;
		min-width: 0;
	}
	.plot {
		position: relative;
		height: var(--plot-h);
		margin: 1.5rem 0 0 2.75rem;
	}
	.grid {
		position: absolute;
		inset: 0;
	}
	.gridline {
		position: absolute;
		left: 0;
		right: 0;
		border-top: 1px solid var(--kb-color-border);
	}
	.gridline span {
		position: absolute;
		right: calc(100% + var(--kb-space-2));
		top: 0;
		transform: translateY(-50%);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		font-variant-numeric: tabular-nums;
		white-space: nowrap;
	}
	.bars {
		position: absolute;
		inset: 0;
		display: grid;
		grid-template-columns: repeat(var(--n), minmax(0, 1fr));
		align-items: end;
		gap: clamp(1px, 0.4vw, 4px);
	}
	.col {
		position: relative;
		display: flex;
		flex-direction: column;
		justify-content: flex-end;
		height: 100%;
	}
	.stack {
		display: flex;
		flex-direction: column-reverse;
		min-height: 0;
		border-radius: 3px 3px 0 0;
		overflow: hidden;
		transition: height var(--kb-duration-slow) var(--kb-ease-emphasized);
	}
	.seg {
		flex-basis: 0;
		min-height: 1px;
	}
	.current .stack {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 1px;
	}
	.peak {
		position: absolute;
		left: 50%;
		transform: translateX(-50%);
		font: var(--kb-type-caption);
		font-weight: 600;
		color: var(--kb-color-text);
		font-variant-numeric: tabular-nums;
		white-space: nowrap;
	}
	.xlabels {
		display: grid;
		grid-template-columns: repeat(var(--n), minmax(0, 1fr));
		margin: var(--kb-space-1) 0 0 2.75rem;
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		white-space: nowrap;
	}
	.xlabels span {
		overflow: visible;
		min-width: 0;
	}
	.legend {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1) var(--kb-space-4);
		margin: var(--kb-space-3) 0 0;
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
		width: 0.625rem;
		height: 0.625rem;
		border-radius: 2px;
	}
</style>
