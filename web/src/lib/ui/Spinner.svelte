<script lang="ts">
	import { ut } from './messages';

	interface Props {
		size?: number;
		/** Announced to screen readers; omit when the surrounding control already says it is busy. */
		label?: string;
	}
	let { size = 20, label }: Props = $props();
</script>

<span class="spinner" style:--size="{size}px" role={label !== undefined ? 'status' : undefined}>
	<svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true">
		<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-opacity="0.2" stroke-width="2.5" />
		<path d="M21 12a9 9 0 0 0-9-9" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
	</svg>
	{#if label !== undefined}<span class="sr">{label || ut('ui.loading')}</span>{/if}
</span>

<style>
	.spinner {
		display: inline-flex;
		width: var(--size);
		height: var(--size);
		flex: none;
	}
	svg {
		animation: spin 0.8s linear infinite;
	}
	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		svg {
			animation-duration: 2.4s;
		}
	}
	.sr {
		position: absolute;
		width: 1px;
		height: 1px;
		overflow: hidden;
		clip-path: inset(50%);
		white-space: nowrap;
	}
</style>
