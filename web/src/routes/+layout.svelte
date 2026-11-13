<script lang="ts">
	import '../../../assets/card/card.css';
	import '../lib/tokens.css';
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import Toaster from '../lib/components/Toaster.svelte';
	import { t, type MessageKey } from '../lib/i18n';
	import { logout, refreshSession, session, watchConnection } from '../lib/state/session.svelte';

	let { children } = $props();

	const nav: { href: string; label: MessageKey }[] = [
		{ href: '/', label: 'nav.home' },
		{ href: '/review', label: 'nav.review' },
		{ href: '/dictionary', label: 'nav.dictionary' },
		{ href: '/notes', label: 'nav.notes' },
		{ href: '/cards', label: 'nav.cards' },
		{ href: '/decks', label: 'nav.decks' },
		{ href: '/note-types', label: 'nav.noteTypes' },
		{ href: '/media', label: 'nav.media' },
		{ href: '/import', label: 'nav.import' },
		{ href: '/struggle', label: 'nav.struggle' },
		{ href: '/stats', label: 'nav.stats' },
		{ href: '/settings', label: 'nav.settings' }
	];

	const onLogin = $derived(page.url.pathname.startsWith('/login'));

	// Check the cookie once at startup (the status never returns to 'unknown').
	onMount(() => {
		if (session.status === 'unknown') void refreshSession();
	});

	// Guard: unauthenticated users go to /login (remembering where they were), and back out of it.
	$effect(() => {
		if (session.status === 'out' && !onLogin) {
			const back = page.url.pathname + page.url.search;
			void goto(back === '/' ? '/login' : `/login?next=${encodeURIComponent(back)}`, {
				replaceState: true
			});
		} else if (session.status === 'in' && onLogin) {
			void goto('/', { replaceState: true });
		}
	});

	// Live-update stream while logged in; screens subscribe on their own for specific events.
	$effect(() => {
		if (session.status !== 'in') return;
		return watchConnection();
	});

	async function doLogout(): Promise<void> {
		await logout();
	}
</script>

{#if session.status === 'in'}
	<header class="bar">
		<a class="brand" href="/">{t('app.name')}</a>
		<nav aria-label={t('nav.main')}>
			{#each nav as item (item.href)}
				<a
					href={item.href}
					aria-current={page.url.pathname === item.href ? 'page' : undefined}>{t(item.label)}</a
				>
			{/each}
		</nav>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={doLogout}>{t('nav.logout')}</button>
	</header>
	<main>{@render children()}</main>
{:else if onLogin}
	<main>{@render children()}</main>
{:else}
	<p class="loading">{t('app.loading')}</p>
{/if}

<Toaster />

<style>
	.bar {
		display: flex;
		align-items: center;
		gap: var(--kb-space-4);
		padding: var(--kb-space-2) var(--kb-space-4);
		background: var(--kb-surface);
		border-bottom: 1px solid var(--kb-border);
		position: sticky;
		top: 0;
	}
	.brand {
		font-weight: 700;
		color: var(--kb-accent);
		text-decoration: none;
	}
	nav {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-3);
		flex: 1;
	}
	nav a {
		text-decoration: none;
		color: var(--kb-text-muted);
		padding: var(--kb-space-1) var(--kb-space-2);
		border-radius: var(--kb-radius);
	}
	nav a[aria-current='page'] {
		color: var(--kb-text);
		background: var(--kb-surface-2);
	}
	main {
		max-width: 64rem;
		margin: 0 auto;
		padding: var(--kb-space-4);
	}
	.loading {
		text-align: center;
		color: var(--kb-text-muted);
		margin-top: 20vh;
	}
</style>
