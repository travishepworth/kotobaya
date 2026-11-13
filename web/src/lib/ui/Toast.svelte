<script lang="ts">
	import Icon from './Icon.svelte';
	import type { IconName } from './icons';
	import { ut } from './messages';

	interface Props {
		message: string;
		tone?: 'info' | 'success' | 'warning' | 'error';
		/** One optional action, e.g. Undo. */
		action?: { label: string; onclick: () => void };
		ondismiss?: () => void;
	}
	let { message, tone = 'info', action, ondismiss }: Props = $props();

	const glyph: Record<NonNullable<Props['tone']>, IconName> = { info: 'info', success: 'success', warning: 'alert', error: 'alert' };
</script>

<div class="toast {tone}" role={tone === 'error' ? 'alert' : 'status'}>
	<span class="glyph"><Icon name={glyph[tone]} /></span>
	<p>{message}</p>
	{#if action}
		<button type="button" class="action" onclick={action.onclick}>{action.label}</button>
	{/if}
	{#if ondismiss}
		<button type="button" class="dismiss" aria-label={ut('ui.dismiss')} onclick={ondismiss}><Icon name="close" size={18} /></button>
	{/if}
</div>

<style>
	.toast {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-3);
		width: min(26rem, 100%);
		min-height: 3.25rem;
		padding: var(--kb-space-2) var(--kb-space-2) var(--kb-space-2) var(--kb-space-4);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-raised);
		color: var(--kb-color-text);
		box-shadow: var(--kb-elevation-2);
	}
	.glyph {
		display: flex;
		flex: none;
		color: var(--kb-color-primary);
	}
	.success .glyph {
		color: var(--kb-color-grade-good);
	}
	.warning .glyph {
		color: var(--kb-color-grade-hard);
	}
	.error {
		background: var(--kb-color-danger-container);
		border-color: transparent;
		color: var(--kb-color-on-danger-container);
	}
	.error .glyph {
		color: var(--kb-color-on-danger-container);
	}
	p {
		flex: 1 1 10rem;
		margin: 0;
		font: var(--kb-type-body-sm);
		min-width: 0;
	}
	button {
		flex: none;
		min-height: var(--kb-size-touch-min);
		border: 0;
		border-radius: var(--kb-radius-sm);
		background: transparent;
		color: inherit;
		cursor: pointer;
	}
	button:focus-visible {
		outline: 2px solid var(--kb-color-focus);
	}
	.action {
		padding: 0 var(--kb-space-3);
		color: var(--kb-color-link);
		font: var(--kb-type-label);
		font-weight: 600;
	}
	.error .action {
		color: inherit;
		text-decoration: underline;
	}
	.dismiss {
		display: grid;
		place-items: center;
		width: var(--kb-size-touch-min);
		color: var(--kb-color-text-muted);
	}
	.error .dismiss {
		color: inherit;
	}
	button:hover {
		background: var(--kb-color-surface-hover);
	}
	.error button:hover {
		background: color-mix(in oklab, currentColor 10%, transparent);
	}
</style>
