<script lang="ts">
	interface Props {
		/** text: a paragraph of lines. row: list-row placeholders. block: a card-sized box. */
		shape?: 'text' | 'row' | 'block';
		count?: number;
		/** Height of a block (any CSS length); reserve the real content's size so nothing shifts on load. */
		height?: string;
	}
	let { shape = 'text', count = 3, height = '8rem' }: Props = $props();
</script>

<div class="sk" aria-hidden="true">
	{#if shape === 'block'}
		<div class="bar block" style:height></div>
	{:else if shape === 'row'}
		{#each { length: count } as _, i (i)}
			<div class="row">
				<div class="bar dot"></div>
				<div class="lines">
					<div class="bar" style:width="{55 - ((i * 13) % 25)}%"></div>
					<div class="bar thin" style:width="{80 - ((i * 17) % 30)}%"></div>
				</div>
			</div>
		{/each}
	{:else}
		{#each { length: count } as _, i (i)}
			<div class="bar" style:width={i === count - 1 ? '60%' : '100%'}></div>
		{/each}
	{/if}
</div>

<style>
	.sk {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
	}
	.bar {
		height: 0.875rem;
		border-radius: var(--kb-radius-xs);
		background: var(--kb-color-surface-sunken);
		animation: pulse 1.6s ease-in-out infinite;
	}
	.thin {
		height: 0.75rem;
	}
	.block {
		border-radius: var(--kb-radius-lg);
	}
	.row {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		min-height: 3.5rem;
		padding: 0 var(--kb-space-4);
	}
	.dot {
		width: 2rem;
		height: 2rem;
		flex: none;
		border-radius: var(--kb-radius-full);
	}
	.lines {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
	}
	@keyframes pulse {
		50% {
			opacity: 0.55;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.bar {
			animation: none;
		}
	}
</style>
