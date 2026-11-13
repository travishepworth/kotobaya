<script lang="ts" generics="T extends string">
	interface Tab {
		value: T;
		label: string;
		/** Optional count badge (e.g. number of senses). */
		count?: number;
		/** id of the panel this tab controls. */
		controls?: string;
	}
	interface Props {
		tabs: Tab[];
		value: T;
		label: string;
		onchange?: (value: T) => void;
	}
	let { tabs, value = $bindable(), label, onchange }: Props = $props();

	let buttons: HTMLButtonElement[] = $state([]);

	function choose(v: T): void {
		value = v;
		onchange?.(v);
	}

	function onkeydown(e: KeyboardEvent, i: number): void {
		const n = tabs.length;
		const next =
			e.key === 'ArrowRight' ? (i + 1) % n : e.key === 'ArrowLeft' ? (i - 1 + n) % n : e.key === 'Home' ? 0 : e.key === 'End' ? n - 1 : -1;
		if (next < 0) return;
		e.preventDefault();
		choose(tabs[next].value);
		buttons[next]?.focus();
	}
</script>

<div class="tabs" role="tablist" aria-label={label}>
	{#each tabs as tab, i (tab.value)}
		<button
			bind:this={buttons[i]}
			type="button"
			role="tab"
			aria-selected={tab.value === value}
			aria-controls={tab.controls}
			tabindex={tab.value === value ? 0 : -1}
			onclick={() => choose(tab.value)}
			onkeydown={(e) => onkeydown(e, i)}
		>
			{tab.label}
			{#if tab.count !== undefined}<span class="count">{tab.count}</span>{/if}
		</button>
	{/each}
</div>

<style>
	.tabs {
		display: flex;
		gap: var(--kb-space-1);
		border-bottom: 1px solid var(--kb-color-border);
		overflow-x: auto;
		scrollbar-width: none;
	}
	button {
		position: relative;
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-2);
		min-height: var(--kb-size-touch-min);
		padding: var(--kb-space-2) var(--kb-space-4);
		border: 0;
		background: transparent;
		color: var(--kb-color-text-muted);
		font: var(--kb-type-label);
		white-space: nowrap;
		cursor: pointer;
		transition: color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	button::after {
		content: '';
		position: absolute;
		inset-inline: var(--kb-space-3);
		bottom: -1px;
		height: 2px;
		border-radius: 2px 2px 0 0;
		background: var(--kb-color-primary);
		transform: scaleX(0);
		transition: transform var(--kb-duration-base) var(--kb-ease-emphasized);
	}
	button:hover {
		color: var(--kb-color-text);
	}
	button[aria-selected='true'] {
		color: var(--kb-color-text);
		font-weight: 600;
	}
	button[aria-selected='true']::after {
		transform: scaleX(1);
	}
	button:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: -2px;
		border-radius: var(--kb-radius-sm);
	}
	.count {
		min-width: 1.5em;
		padding: 0 var(--kb-space-2);
		text-align: center;
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		color: var(--kb-color-text-muted);
		font: var(--kb-type-caption);
		font-variant-numeric: tabular-nums;
	}
</style>
