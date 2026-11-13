<script lang="ts">
	// Browse frame shared by /cards and /notes: title with the Cards | Notes switch, a toolbar, the master list, and
	// the detail pane (side by side from 1024 px; a dialog / bottom sheet below that).
	import type { Snippet } from 'svelte';
	import { goto } from '$app/navigation';
	import Dialog from '../../lib/ui/Dialog.svelte';
	import SegmentedControl from '../../lib/ui/SegmentedControl.svelte';
	import { tb } from './messages';

	interface Props {
		view: 'cards' | 'notes';
		toolbar: Snippet;
		master: Snippet;
		/** The detail pane's content; null when nothing is picked. */
		detail: Snippet | null;
		placeholder: Snippet;
		detailTitle: string;
		onclose: () => void;
	}
	let { view, toolbar, master, detail, placeholder, detailTitle, onclose }: Props = $props();

	// Side by side once the frame is at least 60 rem wide (so 200 % text gets the sheet instead of a squeezed pane).
	let frame: HTMLDivElement | undefined = $state();
	let wide = $state(true);
	$effect(() => {
		if (!frame) return;
		const el = frame;
		const ro = new ResizeObserver(() => {
			const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
			wide = el.clientWidth >= 60 * rem;
		});
		ro.observe(el);
		return () => ro.disconnect();
	});
	let sheetOpen = $state(false);
	$effect(() => {
		sheetOpen = !wide && detail !== null;
	});
</script>

<div class="browse" class:wide bind:this={frame}>
	<header class="head">
		<h1>{tb('br.title')}</h1>
		<SegmentedControl
			label={tb('br.view')}
			value={view}
			options={[
				{ value: 'cards', label: tb('br.cards'), icon: 'review' },
				{ value: 'notes', label: tb('br.notes'), icon: 'edit' }
			]}
			onchange={(v) => goto(`/${v}`)}
		/>
	</header>
	<div class="toolbar">{@render toolbar()}</div>
	<div class="split">
		<div class="master">{@render master()}</div>
		{#if wide}
			<aside class="detail" aria-label={detailTitle}>
				{#if detail}{@render detail()}{:else}{@render placeholder()}{/if}
			</aside>
		{/if}
	</div>
</div>

{#if !wide && detail}
	<Dialog bind:open={sheetOpen} title={detailTitle} size="lg" {onclose}>
		<div class="sheet">{@render detail()}</div>
	</Dialog>
{/if}

<style>
	.browse {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		padding: var(--kb-space-5) var(--kb-space-4) var(--kb-space-8);
		max-width: 110rem;
		margin: 0 auto;
	}
	.head {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: var(--kb-space-3);
	}
	h1 {
		font: var(--kb-type-headline);
	}
	.toolbar {
		min-width: 0;
	}
	.split {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: var(--kb-space-5);
		align-items: start;
	}
	.master {
		min-width: 0;
	}
	.detail {
		position: sticky;
		top: var(--kb-space-5);
		max-height: calc(100dvh - 2 * var(--kb-space-5));
		overflow-y: auto;
		min-width: 0;
		padding: var(--kb-space-5);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
	}
	.sheet {
		color: var(--kb-color-text);
	}
	@media (min-width: 600px) {
		.browse {
			padding: var(--kb-space-8) var(--kb-space-6) var(--kb-space-10);
		}
	}
	@media (min-width: 1024px) {
		.browse {
			padding: var(--kb-space-10) var(--kb-space-8) var(--kb-space-10);
		}
	}
	.wide .split {
		grid-template-columns: minmax(0, 1fr) minmax(24rem, 40%);
	}
</style>
