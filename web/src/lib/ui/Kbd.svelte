<script lang="ts">
	interface Props {
		/** The key as printed on the keycap ("1", "Space", "⌘K"). */
		keys: string;
	}
	let { keys }: Props = $props();
</script>

<kbd class="kbd">{keys}</kbd>

<style>
	.kbd {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-width: 1.5rem;
		height: 1.5rem;
		padding: 0 var(--kb-space-1);
		border: 1px solid var(--kb-color-border);
		border-bottom-width: 2px;
		border-radius: var(--kb-radius-xs);
		background: var(--kb-color-surface);
		color: var(--kb-color-text-muted);
		font: var(--kb-type-caption);
		font-family: var(--kb-family-sans);
		font-variant-numeric: tabular-nums;
		line-height: 1;
	}
</style>
