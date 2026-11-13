<script lang="ts">
	import type { CoverageCurveInner } from '../state/media.svelte';
	import { tc } from './messages';

	let { curve }: { curve: CoverageCurveInner[] } = $props();

	const W = 400;
	const H = 160;
	const PAD = 24;

	// Only screen positions are computed here; the percentages come from the server.
	const points = $derived.by(() => {
		if (curve.length === 0) return [];
		const maxWords = Math.max(1, ...curve.map((p) => p.words));
		const lo = Math.min(...curve.map((p) => p.knownPct));
		const hi = Math.max(...curve.map((p) => p.knownPct));
		const span = hi - lo || 1;
		return curve.map((p) => ({
			...p,
			x: PAD + (p.words / maxWords) * (W - 2 * PAD),
			y: H - PAD - ((p.knownPct - lo) / span) * (H - 2 * PAD)
		}));
	});
	const path = $derived(points.map((p) => `${p.x},${p.y}`).join(' '));
</script>

<svg viewBox="0 0 {W} {H}" role="img" aria-label={tc('cov.curve')} class="curve">
	<polyline points={path} fill="none" stroke="currentColor" stroke-width="2" />
	{#each points as p (p.words)}
		<circle cx={p.x} cy={p.y} r="3" fill="currentColor">
			<title>{tc('cov.curveLabel', { words: p.words, pct: p.knownPct })}</title>
		</circle>
	{/each}
	<text x={W / 2} y={H - 4} text-anchor="middle" font-size="10">{tc('cov.curveAxis')}</text>
</svg>

<style>
	.curve {
		width: 100%;
		max-width: 32rem;
	}
</style>
