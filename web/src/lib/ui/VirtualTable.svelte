<script lang="ts" generics="Row">
	// A windowed data table for long lists (thousands of cards): only rows near the viewport are in the DOM, between
	// two spacers. Rows are a fixed height in rem (so they grow with the text size), sticky sunken header, optional
	// selection checkboxes, active-row highlight, keyboard activation, "load more" as the end comes into view.
	// Optional columns drop below 600 px of table width (phones keep the essential ones).
	import type { Snippet } from 'svelte';
	import Skeleton from './Skeleton.svelte';
	import type { TableColumn } from './kinds';

	interface Props {
		label: string;
		columns: TableColumn[];
		rows: Row[];
		rowKey: (row: Row) => string;
		cell: Snippet<[Row, TableColumn]>;
		activeKey?: string | null;
		onactivate?: (row: Row) => void;
		/** Row selection (checkbox column). */
		selection?: {
			has: (row: Row) => boolean;
			toggle: (row: Row) => void;
			all: boolean;
			toggleAll: () => void;
			disabled?: boolean;
			label: string;
			allLabel: string;
		};
		loading?: boolean;
		hasMore?: boolean;
		onnearend?: () => void;
		/** Row height in rem. */
		rowRem?: number;
		/** CSS height of the scroll box. */
		height?: string;
		empty?: Snippet;
	}
	let {
		label,
		columns,
		rows,
		rowKey,
		cell,
		activeKey = null,
		onactivate,
		selection,
		loading = false,
		hasMore = false,
		onnearend,
		rowRem = 3.25,
		height = '32rem',
		empty
	}: Props = $props();

	const OVERSCAN = 8;
	let box: HTMLDivElement | undefined = $state();
	let scrollTop = $state(0);
	let viewport = $state(600);
	let rootPx = $state(16);
	const rowPx = $derived(rowRem * rootPx);
	const start = $derived(Math.max(0, Math.floor(scrollTop / rowPx) - OVERSCAN));
	const end = $derived(Math.min(rows.length, Math.ceil((scrollTop + viewport) / rowPx) + OVERSCAN));
	const visible = $derived(rows.slice(start, end));

	const template = $derived(
		[selection ? '3rem' : '', ...columns.map((c) => c.width ?? 'minmax(0, 1fr)')].filter(Boolean).join(' ')
	);
	const narrowTemplate = $derived(
		[selection ? '3rem' : '', ...columns.filter((c) => !c.optional).map((c) => c.width ?? 'minmax(0, 1fr)')]
			.filter(Boolean)
			.join(' ')
	);

	$effect(() => {
		rootPx = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
		if (!box) return;
		const el = box;
		const ro = new ResizeObserver(() => (viewport = el.clientHeight));
		ro.observe(el);
		return () => ro.disconnect();
	});

	function onscroll(): void {
		if (!box) return;
		scrollTop = box.scrollTop;
		if (hasMore && box.scrollTop + box.clientHeight >= box.scrollHeight - rowPx * 20) onnearend?.();
	}

	function onkey(e: KeyboardEvent, row: Row): void {
		if ((e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget) {
			e.preventDefault();
			onactivate?.(row);
		}
		if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
			e.preventDefault();
			const sib = (e.currentTarget as HTMLElement)[e.key === 'ArrowDown' ? 'nextElementSibling' : 'previousElementSibling'];
			if (sib instanceof HTMLElement && sib.getAttribute('role') === 'row') sib.focus();
		}
	}
</script>

<div class="wrap" style:--cols={template} style:--cols-narrow={narrowTemplate} style:--row-h="{rowRem}rem">
	<div class="table" role="table" aria-label={label} aria-rowcount={rows.length + 1} aria-busy={loading || undefined}>
		<div class="row head" role="row" aria-rowindex={1}>
			{#if selection}
				<span class="cell sel" role="columnheader">
					<input
						type="checkbox"
						aria-label={selection.allLabel}
						checked={selection.all}
						disabled={selection.disabled || rows.length === 0}
						onchange={selection.toggleAll}
					/>
				</span>
			{/if}
			{#each columns as c (c.key)}
				<span class="cell" class:end={c.align === 'end'} class:optional={c.optional} role="columnheader">{c.label}</span>
			{/each}
		</div>
		<div class="body" role="rowgroup" bind:this={box} {onscroll} style:height>
			{#if loading && rows.length === 0}
				<div class="state"><Skeleton shape="row" count={6} /></div>
			{:else if rows.length === 0}
				<div class="state">{@render empty?.()}</div>
			{:else}
				<div style:height="{start * rowPx}px" aria-hidden="true"></div>
				{#each visible as row, i (rowKey(row))}
					{@const key = rowKey(row)}
					<div
						class="row"
						class:active={key === activeKey}
						class:picked={selection?.has(row)}
						role="row"
						tabindex="0"
						aria-rowindex={start + i + 2}
						aria-selected={onactivate ? key === activeKey : undefined}
						onclick={() => onactivate?.(row)}
						onkeydown={(e) => onkey(e, row)}
					>
						{#if selection}
							<!-- The whole cell is the checkbox's hit area (≥ 44 px); it never activates the row. -->
							<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions, a11y_no_noninteractive_element_to_interactive_role -->
							<label class="cell sel" role="cell" onclick={(e) => e.stopPropagation()}>
								<input
									type="checkbox"
									aria-label={selection.label}
									checked={selection.has(row)}
									disabled={selection.disabled}
									onchange={() => selection.toggle(row)}
								/>
							</label>
						{/if}
						{#each columns as c (c.key)}
							<span class="cell" class:end={c.align === 'end'} class:optional={c.optional} role="cell" lang={c.lang}>
								{@render cell(row, c)}
							</span>
						{/each}
					</div>
				{/each}
				<div style:height="{(rows.length - end) * rowPx}px" aria-hidden="true"></div>
			{/if}
		</div>
	</div>
</div>

<style>
	.wrap {
		container-type: inline-size;
		min-width: 0;
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
		overflow: hidden;
	}
	.row {
		display: grid;
		grid-template-columns: var(--cols);
		align-items: center;
		height: var(--row-h);
		border-bottom: 1px solid var(--kb-color-border);
		color: var(--kb-color-text);
	}
	.head {
		height: 2.75rem;
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-label);
		color: var(--kb-color-text-muted);
	}
	.body {
		overflow-y: auto;
		overscroll-behavior: contain;
	}
	.body .row {
		cursor: pointer;
		font: var(--kb-type-body-sm);
		transition: background-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.body .row:hover {
		background: var(--kb-color-surface-hover);
	}
	.body .row.picked {
		background: color-mix(in oklab, var(--kb-color-surface-selected) 60%, var(--kb-color-surface));
	}
	.body .row.active {
		background: var(--kb-color-surface-selected);
		box-shadow: inset 3px 0 0 var(--kb-color-primary);
	}
	.body .row:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: -2px;
	}
	.cell {
		min-width: 0;
		padding: 0 var(--kb-space-3);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.cell:lang(ja) {
		font: var(--kb-type-body);
		font-weight: 500;
	}
	.end {
		text-align: end;
		font-variant-numeric: tabular-nums;
	}
	.sel {
		display: grid;
		place-items: center;
		height: 100%;
		padding: 0;
	}
	.sel input {
		width: 1.125rem;
		height: 1.125rem;
		margin: 0;
		accent-color: var(--kb-color-primary);
		cursor: pointer;
	}
	.body .sel {
		cursor: pointer;
	}
	.state {
		padding: var(--kb-space-4);
	}
	@container (max-width: 599.98px) {
		.row {
			grid-template-columns: var(--cols-narrow);
		}
		.optional {
			display: none;
		}
	}
</style>
