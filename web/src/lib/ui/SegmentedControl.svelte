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
	let root: HTMLDivElement | undefined = $state();
	// When the labels do not fit in one row (phones, large text, long labels) the control becomes a
	// stacked radio list instead of cutting the labels off. `overflowAt` is the container width at which
	// they stopped fitting: the row comes back only once there is more room than that.
	let stacked = $state(false);
	let overflowAt = 0;

	$effect(() => {
		const el = root;
		const host = el?.parentElement;
		if (!el || !host) return;
		void options.map((o) => o.label).join();
		const check = (): void => {
			const avail = host.clientWidth;
			if (avail === 0) return;
			if (stacked) {
				if (avail > overflowAt) stacked = false;
				return;
			}
			const cut =
				el.scrollWidth > el.clientWidth + 1 ||
				[...el.querySelectorAll<HTMLElement>('.lbl')].some((l) => l.scrollWidth > l.clientWidth + 1);
			if (cut) {
				overflowAt = avail;
				stacked = true;
			}
		};
		check();
		if (typeof ResizeObserver === 'undefined') return;
		const ro = new ResizeObserver(check);
		ro.observe(host);
		ro.observe(el);
		return () => ro.disconnect();
	});

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

<div class="seg" class:full class:stacked bind:this={root} role="radiogroup" aria-label={label}>
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
			<span class="lbl">{o.label}</span>
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
	.lbl {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	/* Stacked radio list: every label in full, one option per line. */
	.stacked {
		display: flex;
		flex-direction: column;
		width: 100%;
		gap: 0;
		padding: 0;
		border: 0;
		background: transparent;
	}
	.stacked button,
	.stacked button[aria-checked='true'] {
		justify-content: flex-start;
		gap: var(--kb-space-3);
		min-height: var(--kb-size-touch-min);
		padding: var(--kb-space-2) var(--kb-space-1);
		background: transparent;
		box-shadow: none;
		color: var(--kb-color-text);
		font: var(--kb-type-body);
		text-align: start;
		white-space: normal;
	}
	.stacked button::before {
		content: '';
		flex: none;
		width: 1.125rem;
		height: 1.125rem;
		border: 2px solid var(--kb-color-border-strong);
		border-radius: 50%;
		background: var(--kb-color-surface);
	}
	.stacked button[aria-checked='true']::before {
		border-color: var(--kb-color-primary);
		box-shadow: inset 0 0 0 3px var(--kb-color-surface);
		background: var(--kb-color-primary);
	}
	.stacked .lbl {
		overflow: visible;
	}
	/* Tight (phones at large text sizes): less padding before labels ellipsize. */
	@media (max-width: 599.98px) {
		button {
			padding-inline: var(--kb-space-2);
		}
	}
</style>
