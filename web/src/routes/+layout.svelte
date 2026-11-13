<script lang="ts">
	import '../../../assets/card/card.css';
	import '../lib/tokens.css';
	import '../lib/ui/app.css';
	import '../lib/ui/fonts';
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import AppShell from '../lib/ui/AppShell.svelte';
	import IconButton from '../lib/ui/IconButton.svelte';
	import ToastHost from '../lib/ui/ToastHost.svelte';
	import type { NavItem } from '../lib/ui/kinds';
	import { ut } from '../lib/ui/messages';
	import { initTheme } from '../lib/ui/theme.svelte';
	import { logout, refreshSession, session, watchConnection } from '../lib/state/session.svelte';

	let { children } = $props();

	// Before the first render of any screen: the stored light/dark choice.
	initTheme();

	const nav: NavItem[] = [
		{ href: '/', label: ut('app.nav.home'), icon: 'home' },
		{ href: '/review', label: ut('app.nav.review'), icon: 'review' },
		{ href: '/dictionary', label: ut('app.nav.dictionary'), icon: 'dictionary' },
		{ href: '/cards', label: ut('app.nav.browse'), icon: 'browse' },
		{ href: '/decks', label: ut('app.nav.decks'), icon: 'decks' },
		{ href: '/struggle', label: ut('app.nav.struggle'), icon: 'struggle' },
		{ href: '/stats', label: ut('app.nav.stats'), icon: 'stats' },
		{ href: '/media', label: ut('app.nav.media'), icon: 'media' },
		{ href: '/import', label: ut('app.nav.import'), icon: 'import', secondary: true },
		{ href: '/note-types', label: ut('app.nav.noteTypes'), icon: 'noteTypes', secondary: true },
		{ href: '/settings', label: ut('app.nav.settings'), icon: 'settings', secondary: true }
	];

	const path = $derived(page.url.pathname);
	const onLogin = $derived(path.startsWith('/login'));
	// The styleguide frames its own shells; it renders bare.
	const bare = $derived(path.startsWith('/styleguide'));
	const immersive = $derived(path === '/review' || path.startsWith('/review/'));
	// Cards and Notes are one destination (Browse).
	const current = $derived(path.startsWith('/notes') ? '/cards' : path);

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
</script>

{#snippet shellActions()}
	<IconButton icon="logout" label={ut('app.logout')} onclick={() => void logout()} />
{/snippet}

{#if session.status === 'in' && bare}
	{@render children()}
{:else if session.status === 'in'}
	<AppShell items={nav} {current} brand={ut('app.name')} {immersive} actions={shellActions}>
		<main id="main">{@render children()}</main>
	</AppShell>
{:else if onLogin}
	<main id="main">{@render children()}</main>
{:else}
	<div class="boot" role="status">
		<span class="seal" aria-hidden="true">言</span>
		<span class="kb-sr">{ut('app.loading')}</span>
	</div>
{/if}

<ToastHost aboveNav={session.status === 'in' && !immersive && !bare} />

<style>
	main {
		display: block;
		min-height: 100%;
	}
	.boot {
		display: grid;
		place-items: center;
		min-height: 100dvh;
	}
	.seal {
		display: grid;
		place-items: center;
		width: 3rem;
		height: 3rem;
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-brand);
		color: var(--kb-color-surface);
		font: 600 1.75rem/1 var(--kb-family-serif);
		animation: breathe 1.6s var(--kb-ease-standard) infinite alternate;
	}
	@keyframes breathe {
		from {
			opacity: 0.55;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.seal {
			animation: none;
		}
	}
	/* Below 600 px the toast stack sits above the bottom bar; the bar is hidden on compact phones in Review. */
	@media (min-width: 600px) {
		main {
			min-height: 100dvh;
		}
	}
</style>
