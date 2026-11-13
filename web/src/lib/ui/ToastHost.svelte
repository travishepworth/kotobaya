<script lang="ts">
	// The app's toast stack (lib/toasts.svelte.ts): bottom-right on desktop, above the bottom bar on phones.
	import { dismissToast, toasts } from '../toasts.svelte';
	import Toast from './Toast.svelte';

	interface Props {
		/** Lift the stack above the phone bottom navigation (when the shell shows one). */
		aboveNav?: boolean;
	}
	let { aboveNav = true }: Props = $props();
</script>

<div class="host" class:above={aboveNav} aria-live="polite">
	{#each toasts.items as toast (toast.id)}
		<div class="slot">
			<Toast
				message={toast.message}
				tone={toast.kind === 'error' ? 'error' : 'info'}
				ondismiss={() => dismissToast(toast.id)}
			/>
		</div>
	{/each}
</div>

<style>
	.host {
		position: fixed;
		z-index: var(--kb-z-toast);
		inset-inline: var(--kb-space-3);
		bottom: calc(var(--kb-space-3) + env(safe-area-inset-bottom));
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--kb-space-2);
		pointer-events: none;
	}
	.host.above {
		bottom: calc(var(--kb-size-bottom-nav) + var(--kb-space-3) + env(safe-area-inset-bottom));
	}
	.slot {
		display: flex;
		justify-content: center;
		width: 100%;
		pointer-events: auto;
		animation: in var(--kb-duration-base) var(--kb-ease-decelerate);
	}
	@keyframes in {
		from {
			opacity: 0;
			transform: translateY(var(--kb-distance-md));
		}
	}
	@media (min-width: 600px) {
		.host,
		.host.above {
			inset-inline: auto var(--kb-space-6);
			bottom: var(--kb-space-6);
			align-items: flex-end;
			width: min(26rem, calc(100vw - 2 * var(--kb-space-6)));
		}
		.slot {
			justify-content: flex-end;
		}
	}
</style>
