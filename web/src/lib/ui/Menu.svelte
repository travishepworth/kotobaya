<script lang="ts">
	// An overflow menu: an icon button that opens a short list of actions (menu semantics, arrow keys,
	// Escape and outside click close it). Items may show a key hint.
	import IconButton from './IconButton.svelte';
	import Icon from './Icon.svelte';
	import Kbd from './Kbd.svelte';
	import type { IconName } from './icons';
	import type { MenuItem } from './kinds';

	interface Props {
		label: string;
		items: MenuItem[];
		icon?: IconName;
		align?: 'start' | 'end';
	}
	let { label, items, icon = 'more', align = 'end' }: Props = $props();

	let open = $state(false);
	let root: HTMLDivElement | undefined = $state();
	let list: HTMLDivElement | undefined = $state();
	const uid = $props.id();

	function buttons(): HTMLButtonElement[] {
		return [...(list?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])];
	}

	function toggle(): void {
		open = !open;
		if (open) requestAnimationFrame(() => buttons()[0]?.focus());
	}

	function close(focusTrigger = true): void {
		open = false;
		if (focusTrigger) root?.querySelector<HTMLButtonElement>('.trigger button')?.focus();
	}

	function choose(item: MenuItem): void {
		close();
		item.onselect();
	}

	function onkeydown(e: KeyboardEvent): void {
		if (!open) return;
		if (e.key === 'Escape') {
			e.preventDefault();
			e.stopPropagation();
			close();
			return;
		}
		if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Home' || e.key === 'End') {
			e.preventDefault();
			const bs = buttons();
			const i = bs.indexOf(document.activeElement as HTMLButtonElement);
			const n = bs.length;
			const next =
				e.key === 'Home' ? 0 : e.key === 'End' ? n - 1 : e.key === 'ArrowDown' ? (i + 1) % n : (i - 1 + n) % n;
			bs[next]?.focus();
		}
		if (e.key === 'Tab') close(false);
	}

	function onWindowClick(e: MouseEvent): void {
		if (open && root && !root.contains(e.target as Node)) close(false);
	}
</script>

<svelte:window onclick={onWindowClick} />

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="menu" bind:this={root} {onkeydown}>
	<span class="trigger">
		<IconButton {icon} {label} aria-haspopup="menu" aria-expanded={open} aria-controls="menu-{uid}" onclick={toggle} />
	</span>
	{#if open}
		<div class="list {align}" role="menu" id="menu-{uid}" aria-label={label} bind:this={list}>
			{#each items as item (item.label)}
				<button
					type="button"
					role={item.checked === undefined ? 'menuitem' : 'menuitemcheckbox'}
					aria-checked={item.checked}
					class:danger={item.danger}
					disabled={item.disabled}
					onclick={() => choose(item)}
				>
					<span class="glyph">
						{#if item.checked !== undefined}
							{#if item.checked}<Icon name="check" size={18} />{/if}
						{:else if item.icon}<Icon name={item.icon} size={18} />{/if}
					</span>
					<span class="text">{item.label}</span>
					{#if item.key}<span class="key"><Kbd keys={item.key} /></span>{/if}
				</button>
			{/each}
		</div>
	{/if}
</div>

<style>
	.menu {
		position: relative;
		display: inline-flex;
	}
	.list {
		position: absolute;
		top: calc(100% + var(--kb-space-1));
		z-index: var(--kb-z-overlay);
		display: flex;
		flex-direction: column;
		min-width: 14rem;
		max-width: min(20rem, calc(100vw - 2 * var(--kb-space-3)));
		padding: var(--kb-space-1);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-raised);
		box-shadow: var(--kb-elevation-2);
		animation: pop var(--kb-duration-fast) var(--kb-ease-decelerate);
		transform-origin: top right;
	}
	.end {
		right: 0;
	}
	.start {
		left: 0;
		transform-origin: top left;
	}
	@keyframes pop {
		from {
			opacity: 0;
			transform: translateY(calc(-1 * var(--kb-distance-sm)));
		}
	}
	button {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		min-height: var(--kb-size-touch-min);
		padding: 0 var(--kb-space-3);
		border: 0;
		border-radius: var(--kb-radius-sm);
		background: transparent;
		color: var(--kb-color-text);
		font: var(--kb-type-body-sm);
		text-align: start;
		cursor: pointer;
	}
	button:hover:not(:disabled),
	button:focus-visible {
		background: var(--kb-color-surface-hover);
	}
	button:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: -2px;
	}
	button:disabled {
		color: var(--kb-color-text-disabled);
		cursor: default;
	}
	.danger {
		color: var(--kb-color-danger);
	}
	.glyph {
		display: flex;
		width: 18px;
		flex: none;
		color: var(--kb-color-text-muted);
	}
	.text {
		flex: 1;
		min-width: 0;
	}
	.key {
		display: none;
	}
	@media (hover: hover) and (pointer: fine) {
		.key {
			display: inline-flex;
		}
	}
</style>
