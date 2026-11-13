<script lang="ts">
	// Login: a quiet centred panel with the seal, password (and the authenticator code when asked), one button.
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import Button from '../../lib/ui/Button.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { t } from '../../lib/i18n';
	import { login } from '../../lib/state/session.svelte';

	let password = $state('');
	let totp = $state('');
	let needTotp = $state(false);
	let busy = $state(false);
	let error = $state('');
	let retryLeft = $state(0);

	// Rate limit: count down to the moment another try is allowed.
	$effect(() => {
		if (retryLeft <= 0) return;
		const id = setInterval(() => (retryLeft = Math.max(0, retryLeft - 1)), 1000);
		return () => clearInterval(id);
	});

	/** Only same-origin absolute paths; anything else falls back to home (open-redirect guard). */
	function safeNext(raw: string | null): string {
		return raw && raw.startsWith('/') && !raw.startsWith('//') && !raw.startsWith('/login') ? raw : '/';
	}

	async function submit(e: SubmitEvent): Promise<void> {
		e.preventDefault();
		if (retryLeft > 0) return;
		busy = true;
		error = '';
		const r = await login(password, needTotp ? totp.trim() : undefined);
		busy = false;
		if (r.ok) {
			password = '';
			await goto(safeNext(page.url.searchParams.get('next')), { replaceState: true });
		} else if (r.reason === 'totp_required') {
			needTotp = true;
			error = t('login.totpRequired');
		} else if (r.reason === 'invalid') {
			error = t('login.invalid');
		} else if (r.reason === 'rate_limited') {
			retryLeft = r.retryAfter ?? 0;
			error = r.retryAfter !== undefined ? t('login.retryAfter', { seconds: r.retryAfter }) : t('login.rateLimited');
		} else {
			error = t('error.network');
		}
	}
	const message = $derived(retryLeft > 0 ? t('login.retryAfter', { seconds: retryLeft }) : error);
</script>

<svelte:head><title>{t('login.title')} · {t('app.name')}</title></svelte:head>

<div class="screen">
	<form class="panel" onsubmit={submit}>
		<div class="brand">
			<span class="seal" aria-hidden="true">言</span>
			<h1>{t('app.name')}</h1>
		</div>
		<!-- svelte-ignore a11y_autofocus -->
		<TextField
			label={t('login.password')}
			type="password"
			autocomplete="current-password"
			required
			autofocus
			bind:value={password}
			error={!needTotp && message ? message : undefined}
		/>
		{#if needTotp}
			<!-- svelte-ignore a11y_autofocus -->
			<TextField
				label={t('login.totp')}
				inputmode="numeric"
				autocomplete="one-time-code"
				maxlength={8}
				autofocus
				bind:value={totp}
				error={message || undefined}
			/>
		{/if}
		<Button variant="primary" size="lg" type="submit" full loading={busy} disabled={retryLeft > 0}>{t('login.submit')}</Button>
	</form>
</div>

<style>
	.screen {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		place-items: center;
		min-height: 100dvh;
		padding: var(--kb-space-6) var(--kb-space-4);
		background: var(--kb-color-bg);
	}
	.panel {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
		width: min(100%, 25rem);
		padding: var(--kb-space-8) var(--kb-space-6);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-xl);
		background: var(--kb-color-surface);
		box-shadow: var(--kb-elevation-1);
	}
	.brand {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--kb-space-3);
		margin-bottom: var(--kb-space-2);
	}
	.seal {
		display: grid;
		place-items: center;
		width: 3.5rem;
		height: 3.5rem;
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-brand);
		color: var(--kb-color-surface);
		font: 600 2rem/1 var(--kb-family-serif);
	}
	h1 {
		font: var(--kb-type-headline);
	}
</style>
