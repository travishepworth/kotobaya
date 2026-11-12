<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { t } from '../../lib/i18n';
	import { login } from '../../lib/session.svelte';

	let password = $state('');
	let totp = $state('');
	let needTotp = $state(false);
	let busy = $state(false);
	let error = $state('');

	/** Only same-origin absolute paths; anything else falls back to home (open-redirect guard). */
	function safeNext(raw: string | null): string {
		return raw && raw.startsWith('/') && !raw.startsWith('//') && !raw.startsWith('/login')
			? raw
			: '/';
	}

	async function submit(e: SubmitEvent): Promise<void> {
		e.preventDefault();
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
			error =
				r.retryAfter !== undefined
					? t('login.retryAfter', { seconds: r.retryAfter })
					: t('login.rateLimited');
		} else {
			error = t('error.network');
		}
	}
</script>

<svelte:head><title>{t('login.title')} · {t('app.name')}</title></svelte:head>

<form onsubmit={submit} class="card">
	<h1>{t('app.name')}</h1>
	<label>
		{t('login.password')}
		<!-- svelte-ignore a11y_autofocus -->
		<input
			class="kb-input"
			type="password"
			autocomplete="current-password"
			required
			autofocus
			bind:value={password}
		/>
	</label>
	{#if needTotp}
		<label>
			{t('login.totp')}
			<input
				class="kb-input"
				type="text"
				inputmode="numeric"
				autocomplete="one-time-code"
				pattern={'[0-9]{6}'}
				maxlength="6"
				required
				bind:value={totp}
			/>
		</label>
	{/if}
	{#if error}<p class="error" role="alert">{error}</p>{/if}
	<button class="kb-btn" type="submit" disabled={busy}>
		{busy ? t('login.submitting') : t('login.submit')}
	</button>
</form>

<style>
	.card {
		max-width: 22rem;
		margin: 15vh auto 0;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		padding: var(--kb-space-6);
		background: var(--kb-surface);
		border: 1px solid var(--kb-border);
		border-radius: var(--kb-radius);
	}
	h1 {
		margin: 0;
		color: var(--kb-accent);
		text-align: center;
	}
	label {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
	}
	.error {
		margin: 0;
		color: var(--kb-error);
	}
</style>
