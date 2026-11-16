<script lang="ts">
	// Sync status of the phone: last sync, ops waiting to upload, the 48 h warning. Tap = sync now (or pair again).
	// Shown in shell mode only; fed by `shell.sync` events (state/device.svelte.ts).
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import Icon from './Icon.svelte';
	import { sh } from './shellMessages';
	import { syncView } from './syncView';
	import { syncNow, syncStatus, watchSyncStatus } from '../state/device.svelte';
	import { has, shell } from '../state/shell.svelte';

	let now = $state(Date.now());
	onMount(() => {
		watchSyncStatus();
		const id = setInterval(() => (now = Date.now()), 30_000);
		return () => clearInterval(id);
	});

	const status = $derived(syncStatus.value);
	const view = $derived(status ? syncView(status, now) : null);
	const pending = $derived(status?.pendingOps ?? 0);
	const label = $derived(view ? sh(view.key, view.params) : '');
	const full = $derived(pending > 0 && view ? `${label}, ${sh('sync.pending', { n: pending })}` : label);

	function tap(): void {
		if (view?.needsPairing) void goto('/login');
		else void syncNow();
	}
</script>

{#if shell.present && has('sync') && view}
	<button type="button" class="sync-chip {view.tone}" aria-label={`${sh('sync.chip.label')}: ${full}`} title={full} onclick={tap}>
		<span class="glyph" class:spin={view.tone === 'busy'}><Icon name="sync" size={16} /></span>
		<span class="text">{label}</span>
		{#if pending > 0}<span class="count kb-num">{pending}</span>{/if}
	</button>
{/if}

<style>
	.sync-chip {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
		min-height: var(--kb-size-touch-min);
		max-width: 100%;
		min-width: 0;
		padding: 0 var(--kb-space-3);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface);
		color: var(--kb-color-text-muted);
		font: var(--kb-type-caption);
		cursor: pointer;
	}
	.sync-chip:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
	.sync-chip.warn {
		color: var(--kb-color-text);
		border-color: var(--kb-color-warning, var(--kb-color-border));
	}
	.sync-chip.error {
		color: var(--kb-color-error, var(--kb-color-text));
		border-color: var(--kb-color-error, var(--kb-color-border));
	}
	.glyph {
		display: grid;
		flex: none;
	}
	.text {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.count {
		flex: none;
		min-width: 1.25rem;
		padding: 0 var(--kb-space-1);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
		text-align: center;
	}
	.spin {
		animation: spin 1.2s linear infinite;
	}
	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.spin {
			animation: none;
		}
	}
</style>
