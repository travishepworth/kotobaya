<script lang="ts">
	import { ut } from './messages';

	interface Props {
		value: number;
		max: number;
		/** Accessible name ("Review progress"). */
		label: string;
		/** Shows "12 of 85" next to the bar. */
		showCount?: boolean;
	}
	let { value, max, label, showCount = false }: Props = $props();
	const pct = $derived(max > 0 ? Math.min(100, Math.max(0, (value / max) * 100)) : 0);
</script>

<div class="progress">
	<div
		class="track"
		role="progressbar"
		aria-label={label}
		aria-valuemin={0}
		aria-valuemax={max}
		aria-valuenow={value}
		aria-valuetext={ut('ui.progress', { done: value, total: max })}
	>
		<div class="fill" style:width="{pct}%"></div>
	</div>
	{#if showCount}<span class="count">{ut('ui.progress', { done: value, total: max })}</span>{/if}
</div>

<style>
	.progress {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		min-width: 0;
	}
	.track {
		flex: 1;
		height: 4px;
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-border);
		overflow: hidden;
	}
	.fill {
		height: 100%;
		border-radius: inherit;
		background: var(--kb-color-primary);
		transition: width var(--kb-duration-slow) var(--kb-ease-emphasized);
	}
	.count {
		flex: none;
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		font-variant-numeric: tabular-nums;
	}
</style>
