<script lang="ts" generics="T extends string">
	import Icon from './Icon.svelte';
	import type { IconName } from './icons';

	interface Option {
		value: T;
		label: string;
		icon?: IconName;
	}
	interface Props {
		options: Option[];
		value: T;
		/** Accessible name of the group. */
		label: string;
		onchange?: (value: T) => void;
		full?: boolean;
	}
	let { options, value = $bindable(), label, onchange, full = false }: Props = $props();

	let buttons: HTMLButtonElement[] = $state([]);

	function choose(v: T): void {
		value = v;
		onchange?.(v);
	}

	// Radio-group keyboard model: arrows move and select, Home/End jump.
	function onkeydown(e: KeyboardEvent, i: number): void {
		const n = options.length;
		const next =
			e.key === 'ArrowRight' || e.key === 'ArrowDown'
				? (i + 1) % n
				: e.key === 'ArrowLeft' || e.key === 'ArrowUp'
					? (i - 1 + n) % n
					: e.key === 'Home'
						? 0
						: e.key === 'End'
							? n - 1
							: -1;
		if (next < 0) return;
		e.preventDefault();
		choose(options[next].value);
		buttons[next]?.focus();
	}
</script>

<div class="seg" class:full role="radiogroup" aria-label={label}>
	{#each options as o, i (o.value)}
		<button
			bind:this={buttons[i]}
			type="button"
			role="radio"
			aria-checked={o.value === value}
			tabindex={o.value === value ? 0 : -1}
			onclick={() => choose(o.value)}
			onkeydown={(e) => onkeydown(e, i)}
		>
			{#if o.icon}<Icon name={o.icon} size={18} />{/if}
			<span>{o.label}</span>
		</button>
	{/each}
</div>

<style>
	.seg {
		display: inline-flex;
		gap: var(--kb-space-0-5);
		padding: 3px;
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-sunken);
		border: 1px solid var(--kb-color-border);
		max-width: 100%;
	}
	.full {
		display: flex;
	}
	.full button {
		flex: 1 1 0;
	}
	button {
		flex: 1 1 auto;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: var(--kb-space-2);
		min-height: calc(var(--kb-size-control) - 8px);
		min-width: 0;
		padding: var(--kb-space-1) var(--kb-space-4);
		border: 0;
		border-radius: calc(var(--kb-radius-md) - 3px);
		background: transparent;
		color: var(--kb-color-text-muted);
		font: var(--kb-type-label);
		cursor: pointer;
		white-space: nowrap;
		transition:
			background-color var(--kb-duration-fast) var(--kb-ease-standard),
			color var(--kb-duration-fast) var(--kb-ease-standard),
			box-shadow var(--kb-duration-fast) var(--kb-ease-standard);
	}
	button:hover {
		color: var(--kb-color-text);
	}
	button[aria-checked='true'] {
		background: var(--kb-color-surface-raised);
		color: var(--kb-color-text);
		box-shadow: var(--kb-elevation-1);
	}
	button:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 1px;
	}
	span {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	/* Tight (phones at large text sizes): less padding before labels ellipsize. */
	@media (max-width: 599.98px) {
		button {
			padding-inline: var(--kb-space-2);
		}
	}
</style>
