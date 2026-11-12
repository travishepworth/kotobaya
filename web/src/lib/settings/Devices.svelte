<script lang="ts">
	import qrcode from 'qrcode-generator';
	import type { Device, Pairing } from '../api/generated';
	import { devicesApi } from './api';
	import { secondsLeft } from './keys';
	import { ts } from './messages';

	let devices = $state<Device[]>([]);
	let pairing = $state<Pairing | null>(null);
	let qrSvg = $state('');
	let now = $state(new Date());
	let renaming = $state<{ id: string; name: string } | null>(null);

	async function load(): Promise<void> {
		try {
			devices = (await devicesApi.listDevices()).items;
		} catch {
			/* toast shown */
		}
	}
	$effect(() => {
		void load();
	});

	// Tick once a second while a code is shown, to drive the countdown.
	$effect(() => {
		if (!pairing) return;
		const id = setInterval(() => (now = new Date()), 1000);
		return () => clearInterval(id);
	});

	const left = $derived(pairing ? secondsLeft(pairing.expiresAt, now) : 0);
	const expired = $derived(pairing !== null && left === 0);

	async function startPairing(): Promise<void> {
		try {
			const p = await devicesApi.createPairing();
			const qr = qrcode(0, 'M');
			qr.addData(p.pairingUri);
			qr.make();
			qrSvg = qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true });
			now = new Date();
			pairing = p;
		} catch {
			/* toast shown */
		}
	}

	async function revoke(d: Device): Promise<void> {
		if (!confirm(ts('devices.confirmRevoke', { name: d.name }))) return;
		try {
			await devicesApi.revokeDevice({ deviceId: d.id });
			await load();
		} catch {
			/* toast shown */
		}
	}

	async function commitRename(e: SubmitEvent): Promise<void> {
		e.preventDefault();
		if (!renaming || renaming.name.trim() === '') return;
		try {
			await devicesApi.updateDevice({
				deviceId: renaming.id,
				devicePatch: { name: renaming.name.trim() }
			});
			renaming = null;
			await load();
		} catch {
			/* toast shown */
		}
	}
</script>

<section>
	<h2>{ts('devices.title')}</h2>
	{#if devices.length === 0}
		<p>{ts('devices.none')}</p>
	{/if}
	<ul>
		{#each devices as d (d.id)}
			<li class:revoked={d.revokedAt !== null}>
				{#if renaming?.id === d.id}
					<form onsubmit={commitRename}>
						<input type="text" aria-label={ts('devices.rename')} bind:value={renaming.name} />
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
						<button class="kb-btn kb-btn-quiet" type="button" onclick={() => (renaming = { id: d.id, name: d.name })}>
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

	<button class="kb-btn" type="button" onclick={startPairing}>{ts('devices.pair')}</button>
	{#if pairing}
		<div class="pairing">
			<p>{ts('devices.pairHelp')}</p>
			{#if expired}
				<p role="status">{ts('devices.pairExpired')}</p>
			{:else}
				<div class="qr" role="img" aria-label={ts('devices.qrLabel')}>
					<!-- eslint-disable-next-line svelte/no-at-html-tags -- SVG generated locally from our own server's URI -->
					{@html qrSvg}
				</div>
				<p><code>{ts('devices.pairCode', { code: pairing.code })}</code></p>
				<p>
					{ts('devices.pairExpiresIn', {
						mm: String(Math.floor(left / 60)).padStart(2, '0'),
						ss: String(left % 60).padStart(2, '0')
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
