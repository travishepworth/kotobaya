<script lang="ts">
	import type { Snippet } from 'svelte';
	import Icon from './Icon.svelte';
	import type { IconName } from './icons';

	interface Props {
		title: string;
		body?: string;
		/** An icon in a soft disc, or "enso" (the brushed circle) for the review-finished moment. */
		art?: IconName | 'enso';
		tone?: 'neutral' | 'error';
		/** page: centred in the content area. inline: inside a panel or list. */
		size?: 'page' | 'inline';
		action?: Snippet;
	}
	let { title, body, art = 'info', tone = 'neutral', size = 'inline', action }: Props = $props();
</script>

<div class="empty {size} {tone}" role={tone === 'error' ? 'alert' : undefined}>
	{#if art === 'enso'}
		<svg class="enso" viewBox="0 0 120 120" aria-hidden="true">
			<path
				d="M93 28c-9-9-21-13-34-12C33 18 15 38 16 62c1 24 21 43 46 42 24-1 42-20 42-43 0-9-3-17-7-24"
				fill="none"
				stroke="currentColor"
				stroke-width="7"
				stroke-linecap="round"
			/>
		</svg>
	{:else}
		<span class="disc"><Icon name={art} size={size === 'page' ? 28 : 22} /></span>
	{/if}
	<h3>{title}</h3>
	{#if body}<p>{body}</p>{/if}
	{#if action}<div class="action">{@render action()}</div>{/if}
</div>

<style>
	.empty {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-align: center;
		gap: var(--kb-space-2);
		padding: var(--kb-space-8) var(--kb-space-4);
		color: var(--kb-color-text);
	}
	.page {
		padding-block: clamp(var(--kb-space-12), 12vh, var(--kb-space-24));
	}
	.disc {
		display: grid;
		place-items: center;
		width: 3.5rem;
		height: 3.5rem;
		margin-bottom: var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		color: var(--kb-color-text-muted);
	}
	.page .disc {
		width: 4.5rem;
		height: 4.5rem;
	}
	.error .disc {
		background: var(--kb-color-danger-container);
		color: var(--kb-color-on-danger-container);
	}
	.enso {
		width: 6rem;
		height: 6rem;
		margin-bottom: var(--kb-space-3);
		color: var(--kb-color-brand);
		opacity: 0.9;
	}
	h3 {
		margin: 0;
		font: var(--kb-type-title);
	}
	.page h3 {
		font: var(--kb-type-headline);
	}
	p {
		margin: 0;
		max-width: 28rem;
		font: var(--kb-type-body);
		color: var(--kb-color-text-muted);
	}
	.action {
		margin-top: var(--kb-space-3);
		display: flex;
		gap: var(--kb-space-2);
		flex-wrap: wrap;
		justify-content: center;
	}
</style>
