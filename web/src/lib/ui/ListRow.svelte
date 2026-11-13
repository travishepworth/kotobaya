<script lang="ts">
	import type { Snippet } from 'svelte';
	import Icon from './Icon.svelte';

	interface Props {
		title: string;
		/** lang of the title; "ja" for Japanese headwords so the right glyphs and line breaking apply. */
		titleLang?: string;
		/** Second line (reading, gloss, count). Clamped to two lines. */
		supporting?: string;
		supportingLang?: string;
		/** Short trailing text (due date, count). */
		meta?: string;
		href?: string;
		onclick?: () => void;
		selected?: boolean;
		leading?: Snippet;
		trailing?: Snippet;
	}
	let { title, titleLang, supporting, supportingLang, meta, href, onclick, selected = false, leading, trailing }: Props =
		$props();
	const interactive = $derived(href !== undefined || onclick !== undefined);
</script>

{#snippet body()}
	{#if leading}<span class="leading">{@render leading()}</span>{/if}
	<span class="text">
		<span class="title" lang={titleLang}>{title}</span>
		{#if supporting}<span class="supporting" lang={supportingLang}>{supporting}</span>{/if}
	</span>
	{#if meta || trailing || href}
		<!-- One group, so at large text sizes it wraps under the text as a unit (never a lone chevron). -->
		<span class="end">
			{#if meta}<span class="meta">{meta}</span>{/if}
			{#if trailing}<span class="trailing">{@render trailing()}</span>{/if}
			{#if href}<span class="chev"><Icon name="chevronRight" size={18} /></span>{/if}
		</span>
	{/if}
{/snippet}

{#if href}
	<a class="row interactive" class:selected {href} aria-current={selected ? 'true' : undefined}>{@render body()}</a>
{:else if onclick}
	<button type="button" class="row interactive" class:selected aria-pressed={selected} {onclick}>{@render body()}</button>
{:else}
	<div class="row" class:selected class:interactive>{@render body()}</div>
{/if}

<style>
	.row {
		position: relative;
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: var(--kb-space-1) var(--kb-space-3);
		width: 100%;
		min-height: 3.5rem;
		padding: var(--kb-space-3) var(--kb-space-4);
		border: 0;
		border-radius: var(--kb-radius-md);
		background: transparent;
		color: var(--kb-color-text);
		text-align: start;
		text-decoration: none;
		font: var(--kb-type-body);
	}
	.interactive {
		cursor: pointer;
		transition: background-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.interactive:hover {
		background: var(--kb-color-surface-hover);
	}
	.row:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: -2px;
	}
	.selected,
	.selected:hover {
		background: var(--kb-color-surface-selected);
	}
	.selected::before {
		content: '';
		position: absolute;
		inset-block: var(--kb-space-3);
		inset-inline-start: 0;
		width: 3px;
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-primary);
	}
	.leading,
	.trailing {
		display: flex;
		align-items: center;
		flex: none;
	}
	.text {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-0-5);
		min-width: 0;
		flex: 1 1 10rem;
	}
	.title {
		font: var(--kb-type-title-sm);
		overflow-wrap: anywhere;
	}
	.title:lang(ja) {
		word-break: auto-phrase;
		font-weight: 500;
	}
	.supporting {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.end {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: flex-end;
		gap: var(--kb-space-2) var(--kb-space-3);
		margin-inline-start: auto;
		min-width: 0;
	}
	.meta {
		flex: none;
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		font-variant-numeric: tabular-nums;
	}
	.chev {
		flex: none;
		color: var(--kb-color-text-muted);
	}
</style>
