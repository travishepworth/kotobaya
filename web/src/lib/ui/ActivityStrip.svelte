<script lang="ts">
	// Recent days as a row of tiles whose ink deepens with the review count (read-only). Each tile carries its
	// count as a tooltip and in a hidden list; empty days stay as outlined paper.
	interface Day {
		key: string;
		/** Full label, e.g. "Fri 13 Nov". */
		label: string;
		/** One-letter weekday for the axis. */
		short: string;
		value: number;
	}
	interface Props {
		caption: string;
		days: Day[];
		format?: (n: number) => string;
	}
	let { caption, days, format = (n) => n.toLocaleString() }: Props = $props();
	const max = $derived(Math.max(1, ...days.map((d) => d.value)));
	function level(v: number): number {
		if (v <= 0) return 0;
		return Math.min(4, Math.ceil((v / max) * 4));
	}
</script>

<figure class="strip">
	<figcaption class="kb-sr">{caption}</figcaption>
	<ol class="days" style:--n={days.length}>
		{#each days as d (d.key)}
			<li title="{d.label}: {format(d.value)}">
				<span class="tile l{level(d.value)}"></span>
				<span class="wd" aria-hidden="true">{d.short}</span>
				<span class="kb-sr">{d.label}: {format(d.value)}</span>
			</li>
		{/each}
	</ol>
</figure>

<style>
	.strip {
		margin: 0;
	}
	.days {
		display: grid;
		grid-template-columns: repeat(var(--n), minmax(0, 1fr));
		gap: clamp(2px, 0.6vw, 6px);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	li {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--kb-space-1);
		min-width: 0;
	}
	.tile {
		width: 100%;
		aspect-ratio: 1;
		max-height: 2.5rem;
		border-radius: var(--kb-radius-xs);
		border: 1px solid var(--kb-color-border);
		background: var(--kb-color-surface);
	}
	.l1 {
		background: color-mix(in oklab, var(--kb-color-primary) 22%, var(--kb-color-surface));
		border-color: transparent;
	}
	.l2 {
		background: color-mix(in oklab, var(--kb-color-primary) 45%, var(--kb-color-surface));
		border-color: transparent;
	}
	.l3 {
		background: color-mix(in oklab, var(--kb-color-primary) 70%, var(--kb-color-surface));
		border-color: transparent;
	}
	.l4 {
		background: var(--kb-color-primary);
		border-color: transparent;
	}
	.wd {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
</style>
