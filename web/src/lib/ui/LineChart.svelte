<script lang="ts">
	// A single-series line chart: SVG path (non-scaling stroke, so it stays crisp at any size), HTML axis labels,
	// an optional dashed target line with its label, and a visually hidden data table.
	interface Point {
		key: string;
		label: string;
		short?: string;
		value: number;
	}
	interface Props {
		caption: string;
		points: Point[];
		min?: number;
		max?: number;
		target?: { value: number; label: string };
		height?: string;
		format?: (n: number) => string;
		/** Fill under the line. */
		area?: boolean;
	}
	let { caption, points, min, max, target, height = '9rem', format = (n) => n.toLocaleString(), area = true }: Props = $props();

	const lo = $derived(min ?? Math.min(...points.map((p) => p.value), target?.value ?? Infinity));
	const hi = $derived(max ?? Math.max(...points.map((p) => p.value), target?.value ?? -Infinity));
	const span = $derived(hi - lo || 1);
	const y = (v: number): number => 100 - ((v - lo) / span) * 100;
	const x = (i: number): number => (points.length <= 1 ? 50 : (i / (points.length - 1)) * 100);
	const line = $derived(points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(2)},${y(p.value).toFixed(2)}`).join(' '));
	const fill = $derived(points.length ? `${line} L${x(points.length - 1)},100 L${x(0)},100 Z` : '');
	const last = $derived(points[points.length - 1]);
</script>

<figure class="chart" style:--plot-h={height}>
	<div class="plot" aria-hidden="true">
		<div class="gridline" style:top="0%"><span>{format(hi)}</span></div>
		<div class="gridline" style:top="100%"><span>{format(lo)}</span></div>
		{#if target}
			<div class="target" style:top="{y(target.value)}%"><span>{target.label}</span></div>
		{/if}
		<svg viewBox="0 0 100 100" preserveAspectRatio="none">
			{#if area && fill}<path class="area" d={fill} />{/if}
			<path class="line" d={line} vector-effect="non-scaling-stroke" />
		</svg>
		{#if last}
			<span class="dot" style:left="{x(points.length - 1)}%" style:top="{y(last.value)}%"></span>
		{/if}
	</div>
	{#if points.length > 1}
		<div class="xlabels" aria-hidden="true">
			<span>{points[0].short ?? points[0].label}</span>
			<span>{last?.short ?? last?.label}</span>
		</div>
	{/if}
	<div class="kb-sr"><table>
		<caption>{caption}</caption>
		<tbody>
			{#each points as p (p.key)}<tr><th scope="row">{p.label}</th><td>{format(p.value)}</td></tr>{/each}
		</tbody>
	</table></div>
</figure>

<style>
	.chart {
		position: relative;
		margin: 0;
		min-width: 0;
	}
	.plot {
		position: relative;
		height: var(--plot-h);
		margin: var(--kb-space-2) var(--kb-space-1) 0 3.25rem;
	}
	svg {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		overflow: visible;
	}
	.line {
		fill: none;
		stroke: var(--kb-color-primary);
		stroke-width: 2;
		stroke-linejoin: round;
		stroke-linecap: round;
	}
	.area {
		fill: color-mix(in oklab, var(--kb-color-primary) 12%, transparent);
	}
	.dot {
		position: absolute;
		width: 0.625rem;
		height: 0.625rem;
		border: 2px solid var(--kb-color-surface);
		border-radius: 50%;
		background: var(--kb-color-primary);
		transform: translate(-50%, -50%);
	}
	.gridline,
	.target {
		position: absolute;
		left: 0;
		right: 0;
		border-top: 1px solid var(--kb-color-border);
	}
	.gridline span {
		position: absolute;
		right: calc(100% + var(--kb-space-2));
		transform: translateY(-50%);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		font-variant-numeric: tabular-nums;
		white-space: nowrap;
	}
	.target {
		border-top: 1px dashed var(--kb-color-border-strong);
	}
	.target span {
		position: absolute;
		right: 0;
		bottom: 2px;
		padding: 0 var(--kb-space-1);
		border-radius: var(--kb-radius-xs);
		background: var(--kb-color-surface);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.xlabels {
		display: flex;
		justify-content: space-between;
		margin: var(--kb-space-1) 0 0 3.25rem;
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
</style>
