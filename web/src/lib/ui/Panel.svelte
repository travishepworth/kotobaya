<script lang="ts">
	// A titled panel on the canvas: surface + hairline, a header with an optional link or action, and content.
	// `flush` removes the inner padding for lists that run edge to edge.
	import type { Snippet } from 'svelte';
	import Icon from './Icon.svelte';

	interface Props {
		title?: string;
		/** Small supporting line under the title. */
		subtitle?: string;
		link?: { href: string; label: string };
		flush?: boolean;
		tone?: 'surface' | 'sunken';
		action?: Snippet;
		children: Snippet;
	}
	let { title, subtitle, link, flush = false, tone = 'surface', action, children }: Props = $props();
	const uid = $props.id();
</script>

<section class="panel {tone}" class:flush aria-labelledby={title ? `p-${uid}` : undefined}>
	{#if title || link || action}
		<header>
			<div class="titles">
				{#if title}<h2 id="p-{uid}">{title}</h2>{/if}
				{#if subtitle}<p>{subtitle}</p>{/if}
			</div>
			{#if action}{@render action()}{/if}
			{#if link}
				<a class="link" href={link.href}>{link.label}<Icon name="chevronRight" size={16} /></a>
			{/if}
		</header>
	{/if}
	<div class="body">{@render children()}</div>
</section>

<style>
	.panel {
		container-type: inline-size;
		min-width: 0;
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
	}
	.sunken {
		border-color: transparent;
		background: var(--kb-color-surface-sunken);
	}
	header {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: var(--kb-space-2) var(--kb-space-3);
		padding: var(--kb-space-4) var(--kb-space-4) 0 var(--kb-space-5);
		min-height: 3.5rem;
	}
	.titles {
		min-width: 0;
		flex: 1 1 10rem;
	}
	h2 {
		font: var(--kb-type-title);
		letter-spacing: var(--kb-type-title-tracking);
	}
	.titles p {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.body {
		padding: var(--kb-space-4) var(--kb-space-5) var(--kb-space-5);
	}
	.flush .body {
		padding: var(--kb-space-2) 0 var(--kb-space-2);
	}
	.link {
		display: inline-flex;
		align-items: center;
		gap: 2px;
		min-height: var(--kb-size-touch-min);
		padding: 0 var(--kb-space-2) 0 var(--kb-space-3);
		border-radius: var(--kb-radius-md);
		font: var(--kb-type-label);
		color: var(--kb-color-link);
		text-decoration: none;
	}
	.link:hover {
		background: var(--kb-color-surface-hover);
	}
</style>
