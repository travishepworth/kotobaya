<script lang="ts">
	import { DevicesState, type Device } from '../state/settings.svelte';
	import { ts } from './messages';

	const vm = new DevicesState();

	$effect(() => {
		void vm.load();
	});

	function revoke(d: Device): void {
		if (!confirm(ts('devices.confirmRevoke', { name: d.name }))) return;
		void vm.revoke(d);
	}

	function commitRename(e: SubmitEvent): void {
		e.preventDefault();
		void vm.commitRename();
	}
</script>

<section>
	<h2>{ts('devices.title')}</h2>
	{#if vm.devices.length === 0}
		<p>{ts('devices.none')}</p>
	{/if}
	<ul>
		{#each vm.devices as d (d.id)}
			<li class:revoked={d.revokedAt !== null}>
				{#if vm.renaming?.id === d.id}
					<form onsubmit={commitRename}>
						<input type="text" aria-label={ts('devices.rename')} bind:value={vm.renaming.name} />
						<button class="kb-btn" type="submit">{ts('settings.save')}</button>
					</form>
				{:else}
					<strong>{d.name}</strong> <small>({d.kind})</small>
					<small>
						{d.lastSeenAt
							? ts('devices.lastSeen', { when: d.lastSeenAt.toLocaleString() })
							: ts('devices.neverSeen')}
					</small>
					{#if d.revokedAt}
						<small>{ts('devices.revoked')}</small>
					{:else if d.kind !== 'server'}
						<button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.beginRename(d)}>
							{ts('devices.rename')}
						</button>
						<button class="kb-btn kb-btn-quiet" type="button" onclick={() => revoke(d)}>
							{ts('devices.revoke')}
						</button>
					{/if}
				{/if}
			</li>
		{/each}
	</ul>

	<button class="kb-btn" type="button" onclick={() => vm.startPairing()}>{ts('devices.pair')}</button>
	{#if vm.pairing}
		<div class="pairing">
			<p>{ts('devices.pairHelp')}</p>
			{#if vm.expired}
				<p role="status">{ts('devices.pairExpired')}</p>
			{:else}
				<div class="qr" role="img" aria-label={ts('devices.qrLabel')}>
					<!-- eslint-disable-next-line svelte/no-at-html-tags -- SVG generated locally from our own server's URI -->
					{@html vm.qrSvg}
				</div>
				<p><code>{ts('devices.pairCode', { code: vm.pairing.code })}</code></p>
				<p>
					{ts('devices.pairExpiresIn', {
						mm: String(Math.floor(vm.left / 60)).padStart(2, '0'),
						ss: String(vm.left % 60).padStart(2, '0')
					})}
				</p>
			{/if}
		</div>
	{/if}
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
	.revoked {
		opacity: 0.6;
	}
	.qr {
		width: 16rem;
		background: #fff; /* QR needs a light quiet zone even in dark mode */
	}
	.pairing {
		margin-top: var(--kb-space-3);
	}
	small {
		color: var(--kb-text-muted);
	}
</style>
