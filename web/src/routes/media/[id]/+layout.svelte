<script lang="ts">
	// One media: Coverage · Read · Sentences as link tabs above the page.
	import { page } from '$app/state';
	import Icon from '../../../lib/ui/Icon.svelte';
	import { tc } from '../../../lib/coverage/messages';
	import { mx } from '../messages';

	let { children } = $props();
	const id = $derived(page.params.id ?? '');
	const base = $derived(`/media/${id}`);
	const tabs = $derived([
		{ href: base, label: tc('cov.tab.coverage') },
		{ href: `${base}/read`, label: tc('cov.tab.read') },
		{ href: `${base}/sentences`, label: tc('cov.tab.sentences') }
	]);
	const path = $derived(page.url.pathname.replace(/\/+$/, ''));
</script>

<div class="frame">
	<div class="bar">
		<a class="back" href="/media"><Icon name="chevronLeft" size={18} />{mx('mx.library')}</a>
		<nav aria-label={tc('cov.tabs')}>
			{#each tabs as t (t.href)}
				<a href={t.href} aria-current={path === t.href ? 'page' : undefined}>{t.label}</a>
			{/each}
		</nav>
	</div>
	{@render children()}
</div>

<style>
	.bar {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: var(--kb-space-2) var(--kb-space-4);
		max-width: calc(var(--kb-size-content) + 2 * var(--kb-space-10));
		margin: 0 auto;
		padding: var(--kb-space-4) var(--kb-space-4) 0;
	}
	@media (min-width: 600px) {
		.bar {
			padding-inline: var(--kb-space-6);
		}
	}
	@media (min-width: 1024px) {
		.bar {
			padding-inline: var(--kb-space-10);
		}
	}
	.back {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
		min-height: var(--kb-size-touch-min);
		color: var(--kb-color-text-muted);
		font: var(--kb-type-label);
		text-decoration: none;
	}
	nav {
		display: flex;
		gap: 2px;
		padding: 3px;
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-sunken);
	}
	nav a {
		display: inline-flex;
		align-items: center;
		min-height: var(--kb-size-touch-min);
		padding: 0 var(--kb-space-4);
		border-radius: var(--kb-radius-sm);
		color: var(--kb-color-text-muted);
		font: var(--kb-type-label);
		text-decoration: none;
	}
	nav a[aria-current='page'] {
		background: var(--kb-color-surface);
		color: var(--kb-color-text);
		box-shadow: var(--kb-elevation-1);
	}
	nav a:focus-visible,
	.back:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
	/* The pages below bring their own top padding; tighten it under the bar. */
	.frame :global(.page) {
		padding-top: var(--kb-space-4);
	}
</style>
