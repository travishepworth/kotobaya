<script lang="ts">
	import type { HTMLButtonAttributes } from 'svelte/elements';
	import Icon from './Icon.svelte';
	import type { IconName } from './icons';

	interface Props extends Omit<HTMLButtonAttributes, 'children'> {
		icon: IconName;
		/** Required: the accessible name and the tooltip. */
		label: string;
		variant?: 'quiet' | 'tonal' | 'outline';
		pressed?: boolean;
		/** Renders a link with the same look (e.g. "back"). */
		href?: string;
		/** With `href`: "_blank" opens the link in a new tab (e.g. from review, so the session stays put). */
		target?: '_blank';
	}
	let { icon, label, variant = 'quiet', pressed, href, target, type = 'button', ...rest }: Props = $props();
</script>

{#if href}
	<a class="ib {variant}" {href} {target} rel={target ? 'noopener' : undefined} aria-label={label} title={label}><Icon name={icon} /></a>
{:else}
	<button class="ib {variant}" {type} aria-label={label} title={label} aria-pressed={pressed} {...rest}>
		<Icon name={icon} />
	</button>
{/if}

<style>
	.ib {
		display: inline-grid;
		place-items: center;
		width: var(--kb-size-touch-min);
		height: var(--kb-size-touch-min);
		flex: none;
		padding: 0;
		border: 1px solid transparent;
		border-radius: var(--kb-radius-full);
		background: transparent;
		color: var(--kb-color-text-muted);
		cursor: pointer;
		transition:
			background-color var(--kb-duration-fast) var(--kb-ease-standard),
			color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.ib:hover:not(:disabled) {
		background: var(--kb-color-surface-hover);
		color: var(--kb-color-text);
	}
	.ib:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
	.tonal {
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
	}
	.outline {
		border-color: var(--kb-color-border-strong);
		color: var(--kb-color-text);
	}
	.ib[aria-pressed='true'] {
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
	}
	.ib:disabled {
		color: var(--kb-color-text-disabled);
		cursor: default;
	}
</style>
