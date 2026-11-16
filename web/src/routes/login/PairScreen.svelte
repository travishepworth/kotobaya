<script lang="ts">
	// Shell mode /login: pair this phone (server URL + code, or a pasted / deep-linked kotobaya://pair URI).
	// `?pair=` (the deep link) prefills the link field. With `auth_required` the same screen re-pairs the same device.
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import Banner from '../../lib/ui/Banner.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { sh } from '../../lib/ui/shellMessages';
	import { t } from '../../lib/i18n';
	import { pairDevice, type PairRequest } from '../../lib/state/device.svelte';
	import { refreshSession } from '../../lib/state/session.svelte';
	import { shell } from '../../lib/state/shell.svelte';

	const PAIR_URI = /^kotobaya:\/\/pair\?/;
	const deepLink = page.url.searchParams.get('pair') ?? '';

	let serverUrl = $state('');
	let code = $state('');
	let uri = $state(PAIR_URI.test(deepLink.trim()) ? deepLink.trim() : '');
	let name = $state('');
	let busy = $state(false);
	let error = $state('');

	const repairing = $derived(shell.info?.authRequired === true);

	/** The request body: a pasted link wins; otherwise the typed server and code; null when neither is complete. */
	function request(): PairRequest | null {
		const n = name.trim();
		const extra = n ? { name: n } : {};
		if (uri.trim()) return { uri: uri.trim(), ...extra };
		if (serverUrl.trim() && code.trim()) return { serverUrl: serverUrl.trim(), code: code.trim(), ...extra };
		return null;
	}

	async function submit(e: SubmitEvent): Promise<void> {
		e.preventDefault();
		const body = request();
		if (!body) {
			error = sh('pair.missing');
			return;
		}
		busy = true;
		error = '';
		const r = await pairDevice(body);
		if (r !== 'ok') {
			busy = false;
			error =
				r === 'invalid' ? sh('pair.invalid') : r === 'unreachable' ? sh('pair.unreachable') : r === 'already' ? sh('pair.already') : r === 'rate_limited' ? t('login.rateLimited') : sh('pair.failed');
			return;
		}
		await refreshSession();
		busy = false;
		await goto('/', { replaceState: true });
	}
</script>

<div class="screen">
	<form class="panel" onsubmit={submit}>
		<div class="brand">
			<span class="seal" aria-hidden="true">言</span>
			<h1>{sh('pair.title')}</h1>
			<p class="lead">{repairing ? sh('pair.reauthLead') : sh('pair.lead')}</p>
		</div>
		<TextField
			label={sh('pair.server')}
			hint={sh('pair.serverHint')}
			type="url"
			inputmode="url"
			autocomplete="url"
			autocapitalize="off"
			spellcheck={false}
			bind:value={serverUrl}
			disabled={uri.trim() !== ''}
		/>
		<TextField
			label={sh('pair.code')}
			autocomplete="one-time-code"
			autocapitalize="characters"
			spellcheck={false}
			maxlength={19}
			bind:value={code}
			disabled={uri.trim() !== ''}
		/>
		<TextField
			label={sh('pair.uri')}
			hint={sh('pair.uriHint')}
			autocapitalize="off"
			spellcheck={false}
			bind:value={uri}
		/>
		<TextField label={sh('pair.name')} maxlength={100} bind:value={name} />
		{#if error}<Banner tone="error">{error}</Banner>{/if}
		<Button variant="primary" size="lg" type="submit" full loading={busy}>{sh('pair.submit')}</Button>
		{#if repairing}<Button variant="quiet" full href="/">{sh('pair.back')}</Button>{/if}
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
		gap: var(--kb-space-4);
		width: min(100%, 25rem);
		padding: var(--kb-space-6) var(--kb-space-5);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-xl);
		background: var(--kb-color-surface);
		box-shadow: var(--kb-elevation-1);
	}
	.brand {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--kb-space-2);
		text-align: center;
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
	}
	h1 {
		font: var(--kb-type-headline);
	}
	.lead {
		margin: 0;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
</style>
