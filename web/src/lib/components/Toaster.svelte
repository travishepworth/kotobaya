<script lang="ts">
	import { dismissToast, toasts } from '../toasts.svelte';
	import { t } from '../i18n';
</script>

<div class="toaster" aria-live="polite">
	{#each toasts.items as toast (toast.id)}
		<div class="toast {toast.kind}" role={toast.kind === 'error' ? 'alert' : 'status'}>
			<span>{toast.message}</span>
			<button type="button" aria-label={t('toast.dismiss')} onclick={() => dismissToast(toast.id)}>
				×
			</button>
		</div>
	{/each}
</div>

<style>
	.toaster {
		position: fixed;
		right: var(--kb-space-4);
		bottom: var(--kb-space-4);
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		max-width: min(28rem, calc(100vw - 2rem));
		z-index: 100;
	}
	.toast {
		display: flex;
		gap: var(--kb-space-3);
		justify-content: space-between;
		align-items: start;
		padding: var(--kb-space-3) var(--kb-space-4);
		border-radius: var(--kb-radius);
		border: 1px solid var(--kb-border);
		background: var(--kb-info-bg);
		box-shadow: var(--kb-shadow);
	}
	.toast.error {
		background: var(--kb-error-bg);
		border-color: var(--kb-error);
	}
	.toast button {
		background: none;
		border: 0;
		cursor: pointer;
		font-size: 1.25rem;
		line-height: 1;
	}
</style>
