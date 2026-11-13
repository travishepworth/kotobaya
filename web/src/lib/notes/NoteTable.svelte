<script lang="ts">
	// Windowed note table (same windowing as the card table).
	import type { Note } from '../api/generated';
	import { ROW_HEIGHT, rowLabel, windowRange } from '../cards/model';
	import type { HeadwordMap } from './headwords.svelte';
	import { tn } from './messages';

	let {
		notes,
		activeId,
		headwords,
		hasMore,
		onactivate,
		onnearend
	}: {
		notes: Note[];
		activeId: string | null;
		headwords: HeadwordMap;
		hasMore: boolean;
		onactivate: (n: Note) => void;
		onnearend: () => void;
	} = $props();

	const VIEWPORT = 480;
	let scrollTop = $state(0);
	let viewport = $state(VIEWPORT);
	let box: HTMLDivElement | undefined = $state();

	const range = $derived(windowRange(notes.length, scrollTop, viewport));
	const labels = $derived(new Map([...headwords.byseq].map(([k, v]) => [k, v.headword])));
	const rows = $derived(notes.slice(range.start, range.end));

	function info(n: Note): { reading: string; gloss: string } | undefined {
		if (n.entrySeq === null) return undefined;
		return headwords.get(n.entrySeq);
	}

	function onscroll(): void {
		if (!box) return;
		scrollTop = box.scrollTop;
		viewport = box.clientHeight;
		if (hasMore && box.scrollTop + box.clientHeight >= box.scrollHeight - ROW_HEIGHT * 20) onnearend();
	}

	$effect(() => {
		if (hasMore && notes.length * ROW_HEIGHT < viewport) onnearend();
	});
</script>

<div class="table" role="table" aria-label={tn('notes.table')} aria-rowcount={notes.length + 1}>
	<div class="row head" role="row">
		<span role="columnheader">{tn('notes.col.note')}</span>
		<span role="columnheader">{tn('notes.col.reading')}</span>
		<span role="columnheader">{tn('notes.col.meaning')}</span>
		<span role="columnheader">{tn('notes.col.type')}</span>
		<span role="columnheader">{tn('notes.col.cards')}</span>
		<span role="columnheader">{tn('notes.col.tags')}</span>
		<span role="columnheader">{tn('notes.col.created')}</span>
	</div>
	<div class="body" bind:this={box} {onscroll} style:height="{VIEWPORT}px">
		<div style:height="{range.padTop}px"></div>
		{#each rows as n, i (n.id)}
			<div
				class="row"
				class:active={n.id === activeId}
				role="row"
				aria-rowindex={range.start + i + 2}
				style:height="{ROW_HEIGHT}px"
			>
				<span role="cell">
					<button type="button" class="link" onclick={() => onactivate(n)}>
						{rowLabel(n, n.id, labels)}
					</button>
				</span>
				<span role="cell">{info(n)?.reading ?? ''}</span>
				<span role="cell">{info(n)?.gloss ?? ''}</span>
				<span role="cell">{n.type}</span>
				<span role="cell">{n.cards.length}</span>
				<span role="cell">{n.tags.join(' ')}</span>
				<span role="cell">{n.createdAt.toLocaleDateString()}</span>
			</div>
		{/each}
		<div style:height="{range.padBottom}px"></div>
	</div>
</div>

<style>
	.table {
		border: 1px solid var(--kb-border);
		border-radius: var(--kb-radius);
		background: var(--kb-surface);
	}
	.body {
		overflow-y: auto;
	}
	.row {
		display: grid;
		grid-template-columns: 1fr 8rem 10rem 6rem 4rem 12rem 8rem;
		align-items: center;
		gap: var(--kb-space-2);
		padding: 0 var(--kb-space-2);
		box-sizing: border-box;
		overflow: hidden;
		white-space: nowrap;
	}
	.row > span {
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.head {
		height: 2rem;
		font-weight: 600;
		background: var(--kb-surface-2);
		border-bottom: 1px solid var(--kb-border);
	}
	.active {
		background: var(--kb-info-bg);
	}
	.link {
		all: unset;
		cursor: pointer;
		color: var(--kb-link);
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.link:focus-visible {
		outline: 2px solid var(--kb-focus);
	}
</style>
