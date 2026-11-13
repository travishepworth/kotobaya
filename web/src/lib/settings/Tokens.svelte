<script lang="ts">
	import { SCOPES, TokensState, type Token } from '../state/settings.svelte';
	import { ts, type SettingsMessageKey } from './messages';

	const vm = new TokensState();
	let dialog: HTMLDialogElement | undefined = $state();

	$effect(() => {
		void vm.load();
	});

	$effect(() => {
		if (vm.secret !== null && dialog && !dialog.open) dialog.showModal();
	});

	function create(e: SubmitEvent): void {
		e.preventDefault();
		void vm.create();
	}

	function closeSecret(): void {
		vm.forgetSecret();
		dialog?.close();
	}

	function revoke(tok: Token): void {
		if (!confirm(ts('tokens.confirmRevoke', { name: tok.name }))) return;
		void vm.revoke(tok);
	}
</script>

<section>
	<h2>{ts('tokens.title')}</h2>
	{#if vm.tokens.length === 0}
		<p>{ts('tokens.none')}</p>
	{/if}
	<ul>
		{#each vm.tokens as tok (tok.id)}
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
		<label>{ts('tokens.name')} <input type="text" maxlength="100" bind:value={vm.name} /></label>
		<fieldset>
			<legend>{ts('tokens.scopes')}</legend>
			{#each SCOPES as s (s)}
				<label class="scope">
					<input type="checkbox" bind:checked={vm.scopes[s]} />
					<span><strong>{ts(`scope.${s}` as SettingsMessageKey)}</strong> — {ts(`scope.${s}.desc` as SettingsMessageKey)}</span>
				</label>
			{/each}
		</fieldset>
		<label>{ts('tokens.expiry')} <input type="datetime-local" bind:value={vm.expiry} /></label>
		<label><input type="checkbox" bind:checked={vm.withDevice} /> {ts('tokens.withDevice')}</label>
		<button class="kb-btn" type="submit" disabled={vm.busy}>{ts('tokens.create')}</button>
	</form>

	<dialog bind:this={dialog} oncancel={(e) => e.preventDefault()}>
		<p>{ts('tokens.shownOnce')}</p>
		<code class="secret">{vm.secret}</code>
		<div class="actions">
			<button class="kb-btn" type="button" onclick={() => vm.copy()}>{ts('tokens.copy')}</button>
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
