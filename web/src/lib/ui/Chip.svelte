<script lang="ts">
	import Icon from './Icon.svelte';
	import type { IconName } from './icons';
	import { ut } from './messages';
	import type { CardState, DeckColor } from './kinds';

	interface Props {
		label: string;
		/**
		 * filter: a toggle (aria-pressed). state: a card state, coloured and with its glyph. deck: a deck with its
		 * colour dot. tag: neutral, optionally removable.
		 */
		kind?: 'filter' | 'state' | 'deck' | 'tag';
		selected?: boolean;
		state?: CardState;
		deck?: DeckColor;
		icon?: IconName;
		lang?: string;
		onclick?: () => void;
		onremove?: () => void;
	}
	let { label, kind = 'tag', selected = false, state = 'new', deck = 'hai', icon, lang, onclick, onremove }: Props = $props();

	const stateIcon: Record<CardState, IconName> = {
		new: 'sparkle',
		learning: 'clock',
		review: 'check',
		relearning: 'undo',
		suspended: 'pause',
		leech: 'flag'
	};
	const glyph = $derived<IconName | undefined>(kind === 'state' ? stateIcon[state] : kind === 'filter' && selected ? 'check' : icon);
	const style = $derived(
		kind === 'state'
			? `--chip-fg: var(--kb-color-state-${state}); --chip-bg: var(--kb-color-state-${state}-container)`
			: kind === 'deck'
				? `--chip-dot: var(--kb-color-deck-${deck})`
				: undefined
	);
</script>

{#snippet inner()}
	{#if kind === 'deck'}<span class="dot" aria-hidden="true"></span>{/if}
	{#if glyph}<Icon name={glyph} size={14} />{/if}
	<span class="label" {lang}>{label}</span>
{/snippet}

{#if kind === 'filter' || onclick}
	<button type="button" class="chip {kind}" class:selected {style} aria-pressed={kind === 'filter' ? selected : undefined} {onclick}>
		{@render inner()}
	</button>
{:else}
	<span class="chip {kind}" {style}>
		{@render inner()}
		{#if onremove}
			<button type="button" class="remove" aria-label={ut('ui.remove', { label })} onclick={onremove}>
				<Icon name="close" size={14} />
			</button>
		{/if}
	</span>
{/if}

<style>
	.chip {
		position: relative;
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
		min-height: 2rem;
		max-width: 100%;
		padding: var(--kb-space-1) var(--kb-space-3);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface);
		color: var(--kb-color-text);
		font: var(--kb-type-label);
		line-height: 1.2;
		vertical-align: middle;
	}
	.label {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	button.chip {
		cursor: pointer;
		transition: background-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	/* 32 px visual, 44 px hit area. */
	button.chip::after {
		content: '';
		position: absolute;
		inset: -6px 0;
	}
	button.chip:hover {
		background: var(--kb-color-surface-hover);
	}
	.chip:focus-visible,
	.remove:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
	.filter.selected,
	.filter.selected:hover {
		background: var(--kb-color-primary-container);
		border-color: transparent;
		color: var(--kb-color-on-primary-container);
	}
	.state {
		border-color: transparent;
		background: var(--chip-bg);
		color: var(--chip-fg);
		font: var(--kb-type-caption);
		font-weight: 600;
		min-height: 1.625rem;
		padding: var(--kb-space-0-5) var(--kb-space-2);
	}
	.dot {
		width: 0.625rem;
		height: 0.625rem;
		flex: none;
		border-radius: var(--kb-radius-full);
		background: var(--chip-dot);
	}
	.remove {
		display: inline-grid;
		place-items: center;
		margin-inline-end: calc(-1 * var(--kb-space-2));
		width: 1.75rem;
		height: 1.75rem;
		border: 0;
		border-radius: var(--kb-radius-full);
		background: transparent;
		color: var(--kb-color-text-muted);
		cursor: pointer;
	}
	.remove:hover {
		background: var(--kb-color-surface-hover);
		color: var(--kb-color-text);
	}
</style>
