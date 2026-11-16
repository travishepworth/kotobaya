<script lang="ts">
	// "This device" › Sync: status, waiting ops, last sync, Sync now.
	import Banner from '../../lib/ui/Banner.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import { sh } from '../../lib/ui/shellMessages';
	import { syncView } from '../../lib/ui/syncView';
	import { syncNow, syncStatus } from '../../lib/state/device.svelte';
	import { shell } from '../../lib/state/shell.svelte';

	let now = $state(Date.now());
	const status = $derived(syncStatus.value);
	const view = $derived(status ? syncView(status, now) : null);
	let busy = $state(false);

	async function run(): Promise<void> {
		busy = true;
		now = Date.now();
		await syncNow();
		busy = false;
	}
</script>

{#if status && view}
	<dl class="rows">
		<div><dt>{sh('dev.sync')}</dt><dd>{sh(view.key, view.params)}</dd></div>
		<div><dt>{sh('dev.lastSync')}</dt><dd>{status.lastSyncAt ? status.lastSyncAt.toLocaleString() : sh('dev.neverSynced')}</dd></div>
		<div><dt>{sh('dev.pending')}</dt><dd class="kb-num">{status.pendingOps}</dd></div>
		{#if status.rejectedOps > 0}<div><dt>{sh('dev.rejected')}</dt><dd class="kb-num">{status.rejectedOps}</dd></div>{/if}
	</dl>
	{#if status.clockWarning}<Banner tone="warning">{sh('dev.clockWarning')}</Banner>{/if}
	{#if status.mintingBlocked}<Banner tone="error">{sh('dev.mintingBlocked')}</Banner>{/if}
	<div class="act">
		<Button variant="tonal" icon="sync" loading={busy || status.phase === 'syncing'} disabled={!shell.online} onclick={run}>
			{sh('dev.syncNow')}
		</Button>
		{#if !shell.online}<span class="hint">{sh('dev.needsOnline')}</span>{/if}
	</div>
{/if}

<style>
	.rows {
		display: grid;
		gap: var(--kb-space-2);
		margin: 0 0 var(--kb-space-3);
	}
	.rows div {
		display: flex;
		justify-content: space-between;
		gap: var(--kb-space-3);
		min-width: 0;
	}
	dt {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	dd {
		margin: 0;
		min-width: 0;
		text-align: end;
		overflow-wrap: anywhere;
	}
	.act {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		flex-wrap: wrap;
		margin-top: var(--kb-space-3);
	}
	.hint {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
</style>
