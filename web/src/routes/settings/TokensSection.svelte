<script lang="ts">
	// Personal API tokens: list with revoke, a create form (scopes explained), and the one-time secret dialog.
	import Button from '../../lib/ui/Button.svelte';
	import Dialog from '../../lib/ui/Dialog.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import Switch from '../../lib/ui/Switch.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { ut } from '../../lib/ui/messages';
	import { ts, type SettingsMessageKey } from '../../lib/settings/messages';
	import { SCOPES, TokensState, type Token } from '../../lib/state/settings.svelte';
	import { sx } from './messages';

	const vm = new TokensState();
	$effect(() => {
		void vm.load();
	});
	let revoking = $state<Token | null>(null);
	let creating = $state(false);
	let secretOpen = $state(false);
	$effect(() => {
		secretOpen = vm.secret !== null;
	});
</script>

{#if vm.tokens.length === 0}
	<EmptyState art="settings" title={sx('sx.noTokens')} body={sx('sx.noTokensBody')} />
{:else}
	<ul class="tokens">
		{#each vm.tokens as tok (tok.id)}
			<li class:inactive={!tok.active}>
				<div class="text">
					<p class="name">{tok.name}</p>
					<p class="meta">
						{sx('sx.kind', { kind: ts(`tokens.kind.${tok.kind}` as SettingsMessageKey), prefix: tok.tokenPrefix })} · {[...tok.scopes].join(', ')}
					</p>
					<p class="meta">
						{tok.lastUsedAt ? ts('tokens.lastUsed', { when: tok.lastUsedAt.toLocaleString() }) : ts('tokens.neverUsed')} ·
						{tok.expiresAt ? ts('tokens.expires', { when: tok.expiresAt.toLocaleDateString() }) : ts('tokens.never')}
					</p>
				</div>
				{#if tok.active}
					<Button size="sm" variant="quiet" onclick={() => (revoking = tok)}>{ts('tokens.revoke')}</Button>
				{:else}
					<span class="off">{ts('tokens.inactive')}</span>
				{/if}
			</li>
		{/each}
	</ul>
{/if}

{#if !creating}
	<div class="new"><Button variant="tonal" icon="plus" onclick={() => (creating = true)}>{sx('sx.newToken')}</Button></div>
{:else}
	<form
		class="create"
		onsubmit={(e) => {
			e.preventDefault();
			void vm.create();
		}}
	>
		<TextField label={ts('tokens.name')} maxlength={100} bind:value={vm.name} />
		<fieldset>
			<legend>{ts('tokens.scopes')}</legend>
			{#each SCOPES as s (s)}
				<Switch
					label={ts(`scope.${s}` as SettingsMessageKey)}
					description={ts(`scope.${s}.desc` as SettingsMessageKey)}
					bind:checked={vm.scopes[s]}
				/>
			{/each}
		</fieldset>
		<TextField label={ts('tokens.expiry')} type="datetime-local" bind:value={vm.expiry} />
		<Switch label={ts('tokens.withDevice')} bind:checked={vm.withDevice} />
		<div class="actions">
			<Button variant="quiet" onclick={() => (creating = false)}>{ut('app.cancel')}</Button>
			<Button variant="primary" type="submit" loading={vm.busy}>{ts('tokens.create')}</Button>
		</div>
	</form>
{/if}

<Dialog open={revoking !== null} title={sx('sx.revokeTitle', { name: revoking?.name ?? '' })} onclose={() => (revoking = null)}>
	<p>{ts('tokens.confirmRevoke', { name: revoking?.name ?? '' })}</p>
	{#snippet actions()}
		<Button variant="quiet" onclick={() => (revoking = null)}>{ut('app.cancel')}</Button>
		<Button
			variant="danger"
			onclick={() => {
				if (revoking) void vm.revoke(revoking);
				revoking = null;
			}}>{ts('tokens.revoke')}</Button
		>
	{/snippet}
</Dialog>

<Dialog bind:open={secretOpen} title={sx('sx.secretTitle')} onclose={() => vm.forgetSecret()}>
	<p>{ts('tokens.shownOnce')}</p>
	<code class="secret">{vm.secret}</code>
	{#snippet actions()}
		<Button variant="outline" onclick={() => vm.copy()}>{ts('tokens.copy')}</Button>
		<Button variant="primary" onclick={() => vm.forgetSecret()}>{ts('tokens.done')}</Button>
	{/snippet}
</Dialog>

<style>
	.tokens {
		margin: 0 0 var(--kb-space-4);
		padding: 0;
		list-style: none;
	}
	li {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2) var(--kb-space-3);
		padding: var(--kb-space-3) 0;
		border-bottom: 1px solid var(--kb-color-border);
	}
	li.inactive .name {
		color: var(--kb-color-text-muted);
	}
	.text {
		flex: 1 1 14rem;
		min-width: 0;
	}
	.name {
		font: var(--kb-type-title-sm);
	}
	.meta {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		overflow-wrap: anywhere;
	}
	.off {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.create {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		padding: var(--kb-space-4);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-sunken);
	}
	fieldset {
		margin: 0;
		padding: 0;
		border: 0;
	}
	legend {
		margin-bottom: var(--kb-space-1);
		font: var(--kb-type-label);
	}
	.actions {
		display: flex;
		justify-content: flex-end;
		gap: var(--kb-space-2);
	}
	.secret {
		display: block;
		margin-top: var(--kb-space-3);
		padding: var(--kb-space-3);
		border-radius: var(--kb-radius-sm);
		background: var(--kb-color-surface-sunken);
		color: var(--kb-color-text);
		font: 0.9375rem/1.5 var(--kb-family-mono);
		overflow-wrap: anywhere;
		user-select: all;
	}
</style>
