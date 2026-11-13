<script lang="ts">
	// The frame of every shell screen: a centred column with the page title, an optional back link, a lead line
	// and page actions. Widths: content (1200), measure (reading, 760), narrow (forms, 640), full (master-detail).
	import type { Snippet } from 'svelte';
	import Icon from './Icon.svelte';

	interface Props {
		title: string;
		titleLang?: string;
		/** Small line above the title (Latin only). */
		eyebrow?: string;
		lead?: string;
		back?: { href: string; label: string };
		width?: 'content' | 'measure' | 'narrow' | 'full';
		/** Visually hide the title (it stays the page's h1). */
		hideTitle?: boolean;
		actions?: Snippet;
		children: Snippet;
	}
	let { title, titleLang, eyebrow, lead, back, width = 'content', hideTitle = false, actions, children }: Props = $props();
</script>

<div class="page {width}">
	<header class="head" class:sr-head={hideTitle}>
		{#if back}
			<a class="back" href={back.href}><Icon name="chevronLeft" size={18} /><span>{back.label}</span></a>
		{/if}
		<div class="row">
			<div class="titles">
				{#if eyebrow}<p class="eyebrow">{eyebrow}</p>{/if}
				<h1 lang={titleLang} class:kb-sr={hideTitle}>{title}</h1>
				{#if lead}<p class="lead">{lead}</p>{/if}
			</div>
			{#if actions}<div class="actions">{@render actions()}</div>{/if}
		</div>
	</header>
	{@render children()}
</div>

<style>
	.page {
		--page-gutter: var(--kb-space-4);
		width: 100%;
		max-width: calc(var(--kb-size-content) + 2 * var(--page-gutter));
		margin: 0 auto;
		padding: var(--kb-space-5) var(--page-gutter) var(--kb-space-12);
	}
	.measure {
		max-width: calc(47.5rem + 2 * var(--page-gutter));
	}
	.narrow {
		max-width: calc(40rem + 2 * var(--page-gutter));
	}
	.full {
		max-width: none;
	}
	@media (min-width: 600px) {
		.page {
			--page-gutter: var(--kb-space-6);
			padding-top: var(--kb-space-8);
		}
	}
	@media (min-width: 1024px) {
		.page {
			--page-gutter: var(--kb-space-10);
			padding-top: var(--kb-space-10);
		}
	}
	.head {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		margin-bottom: var(--kb-space-6);
	}
	.sr-head {
		margin-bottom: 0;
	}
	.row {
		display: flex;
		flex-wrap: wrap;
		align-items: flex-end;
		justify-content: space-between;
		gap: var(--kb-space-3) var(--kb-space-4);
	}
	.titles {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		min-width: 0;
		flex: 1 1 18rem;
	}
	.eyebrow {
		font: var(--kb-type-overline);
		letter-spacing: var(--kb-type-overline-tracking);
		text-transform: uppercase;
		color: var(--kb-color-text-muted);
	}
	h1 {
		font: var(--kb-type-headline);
		letter-spacing: var(--kb-type-headline-tracking);
		overflow-wrap: anywhere;
	}
	h1:lang(ja) {
		overflow-wrap: normal;
	}
	.lead {
		font: var(--kb-type-body);
		color: var(--kb-color-text-muted);
		max-width: 42rem;
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		align-items: center;
	}
	.back {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
		align-self: flex-start;
		min-height: var(--kb-size-touch-min);
		margin: calc(-1 * var(--kb-space-2)) 0 0 calc(-1 * var(--kb-space-2));
		padding: 0 var(--kb-space-3) 0 var(--kb-space-1);
		border-radius: var(--kb-radius-md);
		color: var(--kb-color-text-muted);
		font: var(--kb-type-label);
		text-decoration: none;
	}
	.back:hover {
		background: var(--kb-color-surface-hover);
		color: var(--kb-color-text);
	}
</style>
