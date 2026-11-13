<script lang="ts">
	import type { Snippet } from 'svelte';
	import Dialog from './Dialog.svelte';
	import Icon from './Icon.svelte';
	import ListRow from './ListRow.svelte';
	import type { IconName } from './icons';
	import { ut } from './messages';
	import type { NavItem } from './kinds';

	interface Props {
		items: NavItem[];
		/** The current path; the item whose href is its longest prefix is active. */
		current: string;
		brand: string;
		/** How many primary items the phone bottom bar shows before "More" (max 4 + More). */
		compactCount?: number;
		/** Trailing actions in the phone top bar and the sidebar footer (sync status, account). */
		actions?: Snippet;
		/**
		 * Immersive screens (Review): no top or bottom bar on phones, and the desktop sidebar collapses to the rail.
		 * The screen brings its own header with a way back.
		 */
		immersive?: boolean;
		children: Snippet;
	}
	let { items, current, brand, compactCount = 4, actions, immersive = false, children }: Props = $props();

	const primary = $derived(items.filter((i) => !i.secondary));
	const secondary = $derived(items.filter((i) => i.secondary));
	const bottom = $derived(primary.slice(0, compactCount));
	const overflow = $derived([...primary.slice(compactCount), ...secondary]);
	const active = $derived(
		items
			.filter((i) => (i.href === '/' ? current === '/' : current === i.href || current.startsWith(i.href + '/')))
			.sort((a, b) => b.href.length - a.href.length)[0]?.href
	);
	const overflowActive = $derived(overflow.some((i) => i.href === active));
	let moreOpen = $state(false);
</script>

{#snippet seal()}
	<span class="seal" aria-hidden="true">言</span>
{/snippet}

{#snippet navList(list: NavItem[])}
	<ul>
		{#each list as item (item.href)}
			<li>
				<a class="item" href={item.href} aria-current={item.href === active ? 'page' : undefined}>
					<span class="glyph"><Icon name={item.icon} /></span>
					<span class="text">{item.label}</span>
				</a>
			</li>
		{/each}
	</ul>
{/snippet}

<div class="shell" class:immersive>
	<div class="grid">
		<nav class="side" aria-label={ut('ui.mainNav')}>
			<a class="brand" href="/">{@render seal()}<span class="word">{brand}</span></a>
			{@render navList(primary)}
			<div class="spacer"></div>
			{@render navList(secondary)}
			{#if actions}<div class="side-actions">{@render actions()}</div>{/if}
		</nav>

		<header class="top">
			<a class="brand" href="/">{@render seal()}<span class="word">{brand}</span></a>
			{#if actions}<div class="top-actions">{@render actions()}</div>{/if}
		</header>

		<div class="content">{@render children()}</div>

		<nav class="bottom" aria-label={ut('ui.mainNav')}>
			{#each bottom as item (item.href)}
				<a class="tab" href={item.href} aria-current={item.href === active ? 'page' : undefined}>
					<span class="pill"><Icon name={item.icon} /></span>
					<span class="text">{item.label}</span>
				</a>
			{/each}
			{#if overflow.length}
				<button type="button" class="tab" class:on={overflowActive} aria-haspopup="dialog" onclick={() => (moreOpen = true)}>
					<span class="pill"><Icon name="more" /></span>
					<span class="text">{ut('ui.more')}</span>
				</button>
			{/if}
		</nav>
	</div>
</div>

<Dialog bind:open={moreOpen} title={ut('ui.more')} sheet>
	<div class="more">
		{#each overflow as item (item.href)}
			<ListRow title={item.label} href={item.href} selected={item.href === active}>
				{#snippet leading()}<Icon name={item.icon} />{/snippet}
			</ListRow>
		{/each}
	</div>
</Dialog>

<style>
	/* Container queries, not media queries: the shell adapts to the space it is given (the styleguide frames it). */
	.shell {
		container: shell / inline-size;
		min-height: var(--kb-shell-height, 100dvh);
		background: var(--kb-color-bg);
		color: var(--kb-color-text);
	}
	.grid {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		grid-template-rows: auto 1fr auto;
		grid-template-areas: 'top' 'content' 'bottom';
		min-height: var(--kb-shell-height, 100dvh);
	}
	.content {
		grid-area: content;
		min-width: 0;
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-0-5);
	}
	a {
		color: inherit;
		text-decoration: none;
	}
	.brand {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-3);
		min-height: var(--kb-size-touch-min);
		border-radius: var(--kb-radius-sm);
	}
	.seal {
		display: grid;
		place-items: center;
		width: 2rem;
		height: 2rem;
		flex: none;
		border-radius: var(--kb-radius-sm);
		background: var(--kb-color-brand);
		color: var(--kb-color-surface);
		font: 600 1.125rem/1 var(--kb-family-serif);
	}
	.word {
		font: var(--kb-type-title-sm);
		letter-spacing: 0.01em;
	}
	:is(.item, .tab, .brand):focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}

	/* Phone: slim top bar + bottom navigation. */
	.side {
		display: none;
	}
	.top {
		grid-area: top;
		position: sticky;
		top: 0;
		z-index: var(--kb-z-nav);
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--kb-space-2);
		min-height: var(--kb-size-top-bar);
		padding: 0 var(--kb-space-2) 0 var(--kb-space-4);
		background: color-mix(in oklab, var(--kb-color-bg) 92%, transparent);
		backdrop-filter: blur(8px);
		border-bottom: 1px solid var(--kb-color-border);
	}
	.top-actions,
	.side-actions {
		display: flex;
		align-items: center;
		gap: var(--kb-space-1);
	}
	.bottom {
		grid-area: bottom;
		position: sticky;
		bottom: 0;
		z-index: var(--kb-z-nav);
		display: grid;
		grid-auto-flow: column;
		grid-auto-columns: minmax(0, 1fr);
		min-height: var(--kb-size-bottom-nav);
		padding: var(--kb-space-1) var(--kb-space-1) calc(var(--kb-space-1) + env(safe-area-inset-bottom));
		background: var(--kb-color-surface);
		border-top: 1px solid var(--kb-color-border);
	}
	.tab {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 2px;
		min-height: var(--kb-size-touch-min);
		padding: var(--kb-space-1) 0;
		border: 0;
		border-radius: var(--kb-radius-md);
		background: none;
		color: var(--kb-color-text-muted);
		font: var(--kb-type-caption);
		cursor: pointer;
	}
	.tab .text {
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.pill {
		display: grid;
		place-items: center;
		width: 3.5rem;
		height: 2rem;
		border-radius: var(--kb-radius-full);
		transition: background-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.tab[aria-current='page'],
	.tab.on {
		color: var(--kb-color-text);
		font-weight: 600;
	}
	.tab[aria-current='page'] .pill,
	.tab.on .pill {
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
	}
	.more {
		display: flex;
		flex-direction: column;
		margin: 0 calc(-1 * var(--kb-space-4));
	}

	/* Immersive (Review): the screen owns the whole phone viewport. */
	.immersive .top,
	.immersive .bottom {
		display: none;
	}
	@container shell (max-width: 599.98px) {
		.immersive .grid {
			grid-template-rows: 1fr;
			grid-template-areas: 'content';
		}
	}

	/* Tablet (medium/expanded, 600-1023): navigation rail. */
	@container shell (min-width: 600px) {
		.grid {
			grid-template-columns: var(--kb-size-rail) minmax(0, 1fr);
			grid-template-rows: 1fr;
			grid-template-areas: 'side content';
		}
		.top,
		.bottom {
			display: none;
		}
		.side {
			grid-area: side;
			position: sticky;
			top: 0;
			align-self: start;
			display: flex;
			flex-direction: column;
			align-items: stretch;
			gap: var(--kb-space-1);
			height: var(--kb-shell-height, 100dvh);
			padding: var(--kb-space-3) var(--kb-space-2);
			border-inline-end: 1px solid var(--kb-color-border);
			background: var(--kb-color-surface);
			overflow-y: auto;
		}
		.side .brand {
			justify-content: center;
			margin-bottom: var(--kb-space-3);
		}
		.side .word {
			display: none;
		}
		.spacer {
			flex: 1;
		}
		.item {
			display: flex;
			flex-direction: column;
			align-items: center;
			gap: 2px;
			padding: var(--kb-space-2) 0;
			border-radius: var(--kb-radius-md);
			color: var(--kb-color-text-muted);
			font: var(--kb-type-caption);
			text-align: center;
			transition: background-color var(--kb-duration-fast) var(--kb-ease-standard);
		}
		.item .text {
			max-width: 100%;
			overflow: hidden;
			text-overflow: ellipsis;
			white-space: nowrap;
		}
		.item .glyph {
			display: grid;
			place-items: center;
			width: 3.5rem;
			height: 2rem;
			border-radius: var(--kb-radius-full);
		}
		.item:hover .glyph {
			background: var(--kb-color-surface-hover);
		}
		.item[aria-current='page'] {
			color: var(--kb-color-text);
			font-weight: 600;
		}
		.item[aria-current='page'] .glyph {
			background: var(--kb-color-primary-container);
			color: var(--kb-color-on-primary-container);
		}
		.side-actions {
			flex-direction: column;
		}
	}

	/* Desktop (large, >= 1024): full sidebar. */
	@container shell (min-width: 1024px) {
		.shell:not(.immersive) .grid {
			grid-template-columns: var(--kb-size-sidebar) minmax(0, 1fr);
		}
		.shell:not(.immersive) .side {
			padding: var(--kb-space-4) var(--kb-space-3);
			background: var(--kb-color-bg);
		}
		.shell:not(.immersive) .side .brand {
			justify-content: flex-start;
			padding: 0 var(--kb-space-3);
			margin-bottom: var(--kb-space-5);
		}
		.shell:not(.immersive) .side .word {
			display: inline;
		}
		.shell:not(.immersive) .item {
			flex-direction: row;
			gap: var(--kb-space-3);
			min-height: var(--kb-size-touch-min);
			padding: 0 var(--kb-space-3);
			font: var(--kb-type-label);
			text-align: start;
		}
		.shell:not(.immersive) .item .glyph {
			width: auto;
			height: auto;
		}
		.shell:not(.immersive) .item .glyph,
		.shell:not(.immersive) .item:hover .glyph,
		.shell:not(.immersive) .item[aria-current='page'] .glyph {
			background: none;
		}
		.shell:not(.immersive) .item:hover {
			background: var(--kb-color-surface-hover);
		}
		.shell:not(.immersive) .item[aria-current='page'] {
			background: var(--kb-color-surface-selected);
			color: var(--kb-color-text);
		}
		.shell:not(.immersive) .item[aria-current='page'] .glyph {
			color: var(--kb-color-primary);
		}
		.shell:not(.immersive) .side-actions {
			flex-direction: row;
			padding: var(--kb-space-3) var(--kb-space-1) 0;
			margin-top: var(--kb-space-2);
			border-top: 1px solid var(--kb-color-border);
		}
	}
</style>
