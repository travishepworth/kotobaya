<script lang="ts">
	// Windowed card table: only the rows in (or near) the viewport are in the DOM, between two
	// spacers, so a 5000-row result costs the same as a 50-row one.
	import { ROW_HEIGHT, rowLabel, windowRange, type Card } from '../state/cards.svelte';
	import { tc, type CardsMessageKey } from './messages';

	let {
		cards,
		selected,
		allMatching,
		activeId,
		hasMore,
		headwords,
		onactivate,
		ontoggle,
		ontogglepage,
		onnearend
	}: {
		cards: Card[];
		selected: Set<string>;
		allMatching: boolean;
		activeId: string | null;
		hasMore: boolean;
		headwords: ReadonlyMap<number, string>;
		onactivate: (c: Card) => void;
		ontoggle: (c: Card) => void;
		ontogglepage: () => void;
		onnearend: () => void;
	} = $props();

	const VIEWPORT = 480;
	let scrollTop = $state(0);
	let viewport = $state(VIEWPORT);
	let box: HTMLDivElement | undefined = $state();

	const range = $derived(windowRange(cards.length, scrollTop, viewport));
	const rows = $derived(cards.slice(range.start, range.end));
	const allLoadedSelected = $derived(cards.length > 0 && cards.every((c) => selected.has(c.id)));

	function onscroll(): void {
		if (!box) return;
		scrollTop = box.scrollTop;
		viewport = box.clientHeight;
		if (hasMore && box.scrollTop + box.clientHeight >= box.scrollHeight - ROW_HEIGHT * 20) onnearend();
	}

	// Short results that do not fill the viewport never scroll: ask for more anyway.
	$effect(() => {
		if (hasMore && cards.length * ROW_HEIGHT < viewport) onnearend();
	});

	function due(c: Card): string {
		return c.dueAt ? c.dueAt.toLocaleString() : tc('cards.due.none');
	}
</script>

<div class="table" role="table" aria-label={tc('cards.table')} aria-rowcount={cards.length + 1}>
	<div class="row head" role="row">
		<span role="columnheader" class="sel">
			<input
				type="checkbox"
				aria-label={tc('cards.selectAllLoaded')}
				checked={allMatching || allLoadedSelected}
				disabled={allMatching}
				onchange={ontogglepage}
			/>
		</span>
		<span role="columnheader" class="note">{tc('cards.col.note')}</span>
		<span role="columnheader" class="kind">{tc('cards.col.kind')}</span>
		<span role="columnheader" class="due">{tc('cards.col.due')}</span>
		<span role="columnheader" class="state">{tc('cards.col.state')}</span>
		<span role="columnheader" class="lapses">{tc('cards.col.lapses')}</span>
		<span role="columnheader" class="tags">{tc('cards.col.tags')}</span>
	</div>
	<div class="body" bind:this={box} {onscroll} style:height="{VIEWPORT}px">
		<div style:height="{range.padTop}px"></div>
		{#each rows as c, i (c.id)}
			<div
				class="row"
				class:active={c.id === activeId}
				role="row"
				tabindex="0"
				aria-selected={c.id === activeId}
				aria-rowindex={range.start + i + 2}
				onclick={() => onactivate(c)}
				onkeydown={(e) => {
					if (e.key === 'Enter' && e.target === e.currentTarget) onactivate(c);
				}}
				style:height="{ROW_HEIGHT}px"
			>
				<span role="cell" class="sel">
					<input
						type="checkbox"
						onclick={(e) => e.stopPropagation()}
						aria-label={tc('cards.col.select')}
						checked={allMatching || selected.has(c.id)}
						disabled={allMatching}
						onchange={() => ontoggle(c)}
					/>
				</span>
				<span role="cell" class="note">
					<span class="link">{rowLabel(c.note, c.noteId, headwords)}</span>
				</span>
				<span role="cell" class="kind">{c.kind}</span>
				<span role="cell" class="due">{due(c)}</span>
				<span role="cell" class="state">
					{tc(`cards.state.${c.state}` as CardsMessageKey)}{#if c.suspended}
						· {tc('cards.suspended')}{/if}
				</span>
				<span role="cell" class="lapses">{c.lapses}</span>
				<span role="cell" class="tags">{c.note?.tags.join(' ') ?? ''}</span>
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
		grid-template-columns: 2rem 1fr 6rem 11rem 8rem 4rem 12rem;
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
	.body .row {
		cursor: pointer;
	}
	.row:focus-visible {
		outline: 2px solid var(--kb-focus);
	}
	.link {
		color: var(--kb-link);
	}
</style>
