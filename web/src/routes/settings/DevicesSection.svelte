<script lang="ts">
	// Paired devices (rename, revoke with confirmation) and pairing a new phone by QR code.
	import Banner from '../../lib/ui/Banner.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import Dialog from '../../lib/ui/Dialog.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import Icon from '../../lib/ui/Icon.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { ut } from '../../lib/ui/messages';
	import { ts } from '../../lib/settings/messages';
	import { DevicesState, type Device } from '../../lib/state/settings.svelte';
	import { sx } from './messages';

	const vm = new DevicesState();
	$effect(() => {
		void vm.load();
	});
	let revoking = $state<Device | null>(null);
	const pad = (n: number): string => String(n).padStart(2, '0');
</script>

{#if vm.devices.length === 0}
	<EmptyState art="system" title={sx('sx.noDevices')} body={sx('sx.noDevicesBody')} />
{:else}
	<ul class="devices">
		{#each vm.devices as d (d.id)}
			<li class:revoked={d.revokedAt !== null}>
				<span class="disc"><Icon name={d.kind === 'server' ? 'system' : 'sync'} size={18} /></span>
				{#if vm.renaming?.id === d.id}
					<form
						class="rename"
						onsubmit={(e) => {
							e.preventDefault();
							void vm.commitRename();
						}}
					>
						<TextField label={ts('devices.rename')} hideLabel bind:value={vm.renaming.name} />
						<Button size="sm" variant="primary" type="submit">{ut('app.save')}</Button>
						<Button size="sm" variant="quiet" onclick={() => (vm.renaming = null)}>{ut('app.cancel')}</Button>
					</form>
				{:else}
					<div class="text">
						<p class="name">{d.name} <span class="kind">{d.kind}</span></p>
						<p class="meta">
							{#if d.revokedAt}{ts('devices.revoked')}{:else}{d.lastSeenAt ? ts('devices.lastSeen', { when: d.lastSeenAt.toLocaleString() }) : ts('devices.neverSeen')}{/if}
						</p>
					</div>
					{#if !d.revokedAt && d.kind !== 'server'}
						<div class="acts">
							<Button size="sm" variant="quiet" onclick={() => vm.beginRename(d)}>{ts('devices.rename')}</Button>
							<Button size="sm" variant="quiet" onclick={() => (revoking = d)}>{ts('devices.revoke')}</Button>
						</div>
					{/if}
				{/if}
			</li>
		{/each}
	</ul>
{/if}

<div class="pair">
	<Button variant="tonal" icon="plus" onclick={() => vm.startPairing()}>{ts('devices.pair')}</Button>
	{#if vm.pairing}
		<div class="pairing">
			{#if vm.expired}
				<Banner tone="warning">{ts('devices.pairExpired')}</Banner>
			{:else}
				<div class="qr" role="img" aria-label={ts('devices.qrLabel')}>
					<!-- eslint-disable-next-line svelte/no-at-html-tags -- SVG generated locally from our own server's URI -->
					{@html vm.qrSvg}
				</div>
				<div class="pair-text">
					<p>{ts('devices.pairHelp')}</p>
					<p class="code"><code>{vm.pairing.code}</code></p>
					<p class="meta kb-num">{ts('devices.pairExpiresIn', { mm: pad(Math.floor(vm.left / 60)), ss: pad(vm.left % 60) })}</p>
				</div>
			{/if}
		</div>
	{/if}
</div>

<Dialog open={revoking !== null} title={sx('sx.revokeTitle', { name: revoking?.name ?? '' })} onclose={() => (revoking = null)}>
	<p>{ts('devices.confirmRevoke', { name: revoking?.name ?? '' })}</p>
	{#snippet actions()}
		<Button variant="quiet" onclick={() => (revoking = null)}>{ut('app.cancel')}</Button>
		<Button
			variant="danger"
			onclick={() => {
				if (revoking) void vm.revoke(revoking);
				revoking = null;
			}}>{ts('devices.revoke')}</Button
		>
	{/snippet}
</Dialog>

<style>
	.devices {
		margin: 0 0 var(--kb-space-4);
		padding: 0;
		list-style: none;
	}
	li {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2) var(--kb-space-3);
		min-height: 3.5rem;
		padding: var(--kb-space-2) 0;
		border-bottom: 1px solid var(--kb-color-border);
	}
	li.revoked {
		opacity: 0.7;
	}
	.disc {
		display: grid;
		place-items: center;
		width: 2.25rem;
		height: 2.25rem;
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-sunken);
		color: var(--kb-color-text-muted);
	}
	.text {
		flex: 1 1 12rem;
		min-width: 0;
	}
	.name {
		font: var(--kb-type-title-sm);
		overflow-wrap: anywhere;
	}
	.kind {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.meta {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.acts,
	.rename {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1);
		align-items: center;
	}
	.rename {
		flex: 1 1 16rem;
	}
	.pairing {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-5);
		align-items: center;
		margin-top: var(--kb-space-4);
		padding: var(--kb-space-4);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-sunken);
	}
	.qr {
		width: 12rem;
		padding: var(--kb-space-2);
		border-radius: var(--kb-radius-sm);
		background: #fff;
	}
	.qr :global(svg) {
		display: block;
		width: 100%;
		height: auto;
	}
	.pair-text {
		flex: 1 1 14rem;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
	}
	.code code {
		font: 600 1.5rem/1.2 var(--kb-family-mono);
		letter-spacing: 0.1em;
	}
</style>
