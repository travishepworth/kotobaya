<script lang="ts">
	import { page } from '$app/state';
	import { tc } from '../../../lib/coverage/messages';

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

<nav aria-label={tc('cov.tabs')}>
	{#each tabs as t (t.href)}
		<a href={t.href} aria-current={path === t.href ? 'page' : undefined}>{t.label}</a>
	{/each}
</nav>
{@render children()}

<style>
	nav {
		display: flex;
		gap: var(--kb-space-3);
		margin-bottom: var(--kb-space-3);
	}
	a[aria-current='page'] {
		font-weight: bold;
	}
</style>
