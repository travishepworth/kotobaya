<script lang="ts">
	import type { Snippet } from 'svelte';
	import type { HTMLButtonAttributes } from 'svelte/elements';
	import Icon from './Icon.svelte';
	import Spinner from './Spinner.svelte';
	import type { IconName } from './icons';

	interface Props extends Omit<HTMLButtonAttributes, 'children'> {
		/** primary: the one main action per view. tonal: secondary emphasis. outline/quiet: everything else. */
		variant?: 'primary' | 'tonal' | 'outline' | 'quiet' | 'danger';
		/** sm is for dense desktop toolbars only (its hit area is still 44 px). */
		size?: 'sm' | 'md' | 'lg';
		/** Renders a link styled as a button. */
		href?: string;
		icon?: IconName;
		iconEnd?: IconName;
		loading?: boolean;
		full?: boolean;
		children: Snippet;
	}
	let {
		variant = 'outline',
		size = 'md',
		href,
		icon,
		iconEnd,
		loading = false,
		full = false,
		disabled = false,
		type = 'button',
		children,
		...rest
	}: Props = $props();
</script>

{#if href && !disabled}
	<a class="btn {variant} {size}" class:full {href}>
		{#if icon}<Icon name={icon} size={size === 'lg' ? 22 : 18} />{/if}
		<span class="label">{@render children()}</span>
		{#if iconEnd}<Icon name={iconEnd} size={18} />{/if}
	</a>
{:else}
	<button class="btn {variant} {size}" class:full {type} disabled={disabled || loading} aria-busy={loading || undefined} {...rest}>
		{#if loading}
			<Spinner size={size === 'lg' ? 20 : 16} />
		{:else if icon}
			<Icon name={icon} size={size === 'lg' ? 22 : 18} />
		{/if}
		<span class="label">{@render children()}</span>
		{#if iconEnd && !loading}<Icon name={iconEnd} size={18} />{/if}
	</button>
{/if}

<style>
	.btn {
		position: relative;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: var(--kb-space-2);
		min-height: var(--kb-size-control);
		padding: var(--kb-space-2) var(--kb-space-5);
		border: 1px solid transparent;
		border-radius: var(--kb-radius-md);
		font: var(--kb-type-label);
		letter-spacing: var(--kb-type-label-tracking);
		text-decoration: none;
		cursor: pointer;
		user-select: none;
		-webkit-tap-highlight-color: transparent;
		transition:
			background-color var(--kb-duration-fast) var(--kb-ease-standard),
			border-color var(--kb-duration-fast) var(--kb-ease-standard),
			color var(--kb-duration-fast) var(--kb-ease-standard),
			transform var(--kb-duration-instant) var(--kb-ease-standard);
	}
	.btn:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
	.btn:active:not(:disabled) {
		transform: scale(0.98);
	}
	.full {
		width: 100%;
	}
	.label {
		min-width: 0;
		text-align: center;
	}
	.sm {
		min-height: var(--kb-size-control-sm);
		padding: var(--kb-space-1) var(--kb-space-3);
		border-radius: var(--kb-radius-sm);
	}
	/* Dense buttons keep a 44 px hit area. */
	.sm::after {
		content: '';
		position: absolute;
		inset: calc((var(--kb-size-control-sm) - var(--kb-size-touch-min)) / 2) 0;
	}
	.lg {
		min-height: calc(var(--kb-size-control) + var(--kb-space-3));
		padding: var(--kb-space-3) var(--kb-space-6);
		font: var(--kb-type-title-sm);
	}

	.primary {
		background: var(--kb-color-primary);
		color: var(--kb-color-on-primary);
	}
	.primary:hover:not(:disabled) {
		background: var(--kb-color-primary-hover);
	}
	.tonal {
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
	}
	.tonal:hover:not(:disabled) {
		background: color-mix(in oklab, var(--kb-color-primary-container), var(--kb-color-primary) 10%);
	}
	.outline {
		background: transparent;
		border-color: var(--kb-color-border-strong);
		color: var(--kb-color-text);
	}
	.quiet {
		background: transparent;
		color: var(--kb-color-text);
	}
	.outline:hover:not(:disabled),
	.quiet:hover:not(:disabled) {
		background: var(--kb-color-surface-hover);
	}
	.danger {
		background: var(--kb-color-danger);
		color: var(--kb-color-on-danger);
	}
	.danger:hover:not(:disabled) {
		background: color-mix(in oklab, var(--kb-color-danger), var(--kb-color-text) 12%);
	}
	.btn:disabled {
		cursor: default;
		background: var(--kb-color-surface-sunken);
		border-color: transparent;
		color: var(--kb-color-text-disabled);
	}
	.btn[aria-busy='true'] {
		color: var(--kb-color-text-muted);
	}
</style>
