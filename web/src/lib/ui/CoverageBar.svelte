<script lang="ts">
	// Stacked bar: known / learning / unknown share of a total (media coverage, deck coverage).
	interface Props {
		known: number;
		learning: number;
		unknown: number;
		total: number;
		/** Bar height in px. */
		height?: number;
	}
	let { known, learning, unknown, total, height = 12 }: Props = $props();
	const pct = (n: number): number => (total > 0 ? (n / total) * 100 : 0);
</script>

<div class="bar" aria-hidden="true" style:height="{height}px">
	<span class="known" style:width="{pct(known)}%"></span>
	<span class="learning" style:width="{pct(learning)}%"></span>
	<span class="unknown" style:width="{pct(unknown)}%"></span>
</div>

<style>
	.bar {
		display: flex;
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		overflow: hidden;
	}
	span {
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
</style>
