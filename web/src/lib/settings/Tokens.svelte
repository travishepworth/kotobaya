<script lang="ts">
	import { Scope, type Token } from '../api/generated';
	import { pushToast } from '../toasts.svelte';
	import { tokensApi } from './api';
	import { ts, type SettingsMessageKey } from './messages';

	const SCOPES = [Scope.Read, Scope.Write, Scope.Review, Scope.Import, Scope.Admin] as const;

	let tokens = $state<Token[]>([]);
	let name = $state('');
	let scopes = $state<Record<string, boolean>>({ read: true });
	let expiry = $state('');
	let withDevice = $state(false);
	let busy = $state(false);
	/** The secret, held only while the dialog is open. */
	let secret = $state<string | null>(null);
	let dialog: HTMLDialogElement | undefined = $state();

	async function load(): Promise<void> {
		try {
			tokens = (await tokensApi.listTokens({})).items;
		} catch {
			/* toast shown */
		}
	}
	$effect(() => {
		void load();
	});

	$effect(() => {
		if (secret !== null && dialog && !dialog.open) dialog.showModal();
	});

	async function create(e: SubmitEvent): Promise<void> {
		e.preventDefault();
		const chosen = SCOPES.filter((s) => scopes[s]);
		if (name.trim() === '') return void pushToast(ts('tokens.needName'), 'error');
		if (chosen.length === 0) return void pushToast(ts('tokens.needScope'), 'error');
		let expiresAt: Date | undefined;
		if (expiry !== '') {
			expiresAt = new Date(expiry);
			if (Number.isNaN(expiresAt.getTime()) || expiresAt.getTime() <= Date.now()) {
				return void pushToast(ts('tokens.pastExpiry'), 'error');
			}
		}
		busy = true;
		try {
			const created = await tokensApi.createToken({
				tokenCreate: {
					name: name.trim(),
					scopes: new Set(chosen),
					...(expiresAt ? { expiresAt } : {}),
					withDevice
				}
			});
			secret = created.token;
			name = '';
			expiry = '';
			withDevice = false;
			scopes = { read: true };
			await load();
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}

	async function copy(): Promise<void> {
		if (secret === null) return;
		try {
			await navigator.clipboard.writeText(secret);
			pushToast(ts('tokens.copied'), 'info');
		} catch {
			/* clipboard unavailable: the user can still select the text */
		}
	}

	function closeSecret(): void {
		secret = null; // forget it; it is never retrievable again
		dialog?.close();
	}

	async function revoke(tok: Token): Promise<void> {
		if (!confirm(ts('tokens.confirmRevoke', { name: tok.name }))) return;
		try {
			await tokensApi.revokeToken({ tokenId: tok.id });
			await load();
		} catch {
			/* toast shown */
		}
	}
</script>

<section>
	<h2>{ts('tokens.title')}</h2>
	{#if tokens.length === 0}
		<p>{ts('tokens.none')}</p>
	{/if}
	<ul>
		{#each tokens as tok (tok.id)}
			<li class:inactive={!tok.active}>
				<strong>{tok.name}</strong>
				<small>{ts(`tokens.kind.${tok.kind}` as SettingsMessageKey)} · {tok.tokenPrefix}… · {[...tok.scopes].join(', ')}</small>
				<small>
					{tok.lastUsedAt
						? ts('tokens.lastUsed', { when: tok.lastUsedAt.toLocaleString() })
						: ts('tokens.neverUsed')}
				</small>
				<small>
					{tok.expiresAt ? ts('tokens.expires', { when: tok.expiresAt.toLocaleString() }) : ts('tokens.never')}
				</small>
				{#if tok.active}
					<button class="kb-btn kb-btn-quiet" type="button" onclick={() => revoke(tok)}>
						{ts('tokens.revoke')}
					</button>
				{:else}
					<small>{ts('tokens.inactive')}</small>
				{/if}
			</li>
		{/each}
	</ul>

	<form onsubmit={create}>
		<h3>{ts('tokens.create')}</h3>
		<label>{ts('tokens.name')} <input type="text" maxlength="100" bind:value={name} /></label>
		<fieldset>
			<legend>{ts('tokens.scopes')}</legend>
			{#each SCOPES as s (s)}
				<label class="scope">
					<input type="checkbox" bind:checked={scopes[s]} />
					<span><strong>{ts(`scope.${s}` as SettingsMessageKey)}</strong> — {ts(`scope.${s}.desc` as SettingsMessageKey)}</span>
				</label>
			{/each}
		</fieldset>
		<label>{ts('tokens.expiry')} <input type="datetime-local" bind:value={expiry} /></label>
		<label><input type="checkbox" bind:checked={withDevice} /> {ts('tokens.withDevice')}</label>
		<button class="kb-btn" type="submit" disabled={busy}>{ts('tokens.create')}</button>
	</form>

	<dialog bind:this={dialog} oncancel={(e) => e.preventDefault()}>
		<p>{ts('tokens.shownOnce')}</p>
		<code class="secret">{secret}</code>
		<div class="actions">
			<button class="kb-btn" type="button" onclick={copy}>{ts('tokens.copy')}</button>
			<button class="kb-btn" type="button" onclick={closeSecret}>{ts('tokens.done')}</button>
		</div>
	</dialog>
</section>

<style>
	ul {
		list-style: none;
		padding: 0;
	}
	li {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		align-items: center;
		padding: var(--kb-space-2) 0;
		border-bottom: 1px solid var(--kb-border);
	}
	.inactive {
		opacity: 0.6;
	}
	form {
		display: grid;
		gap: var(--kb-space-2);
		margin-top: var(--kb-space-3);
	}
	label {
		display: flex;
		gap: var(--kb-space-2);
		align-items: center;
	}
	.scope {
		align-items: flex-start;
	}
	.secret {
		display: block;
		word-break: break-all;
		user-select: all;
		margin: var(--kb-space-3) 0;
	}
	.actions {
		display: flex;
		gap: var(--kb-space-2);
	}
	small {
		color: var(--kb-text-muted);
	}
</style>
