<script lang="ts" generics="Row">
	import type { Snippet } from 'svelte';
	import Icon from './Icon.svelte';
	import Skeleton from './Skeleton.svelte';
	import { ut } from './messages';
	import type { TableColumn as Column } from './kinds';

	interface Props {
		columns: Column[];
		rows: Row[];
		rowKey: (row: Row) => string;
		/** Plain cell text; use `cell` for anything richer. */
		text?: (row: Row, column: Column) => string;
		cell?: Snippet<[Row, Column]>;
		caption: string;
		sort?: { key: string; dir: 'asc' | 'desc' };
		onsort?: (key: string) => void;
		selected?: string;
		onselect?: (row: Row) => void;
		loading?: boolean;
		density?: 'comfortable' | 'compact';
		empty?: Snippet;
	}
	let {
		columns,
		rows,
		rowKey,
		text,
		cell,
		caption,
		sort,
		onsort,
		selected,
		onselect,
		loading = false,
		density = 'comfortable',
		empty
	}: Props = $props();

	function onrowkey(e: KeyboardEvent, row: Row): void {
		if (e.key === 'Enter' || e.key === ' ') {
			e.preventDefault();
			onselect?.(row);
		}
	}
</script>

<div class="wrap" class:compact={density === 'compact'}>
	<table aria-busy={loading || undefined}>
		<caption class="sr">{caption}</caption>
		<colgroup>
			{#each columns as c (c.key)}<col class:optional={c.optional} style:width={c.width} />{/each}
		</colgroup>
		<thead>
			<tr>
				{#each columns as c (c.key)}
					<th
						scope="col"
						class:end={c.align === 'end'}
						class:optional={c.optional}
						aria-sort={sort?.key === c.key ? (sort.dir === 'asc' ? 'ascending' : 'descending') : undefined}
					>
						{#if c.sortable && onsort}
							<button type="button" class="sort" onclick={() => onsort(c.key)}>
								{c.label}
								<span class="arrow" class:active={sort?.key === c.key}>
									<Icon name={sort?.key === c.key && sort.dir === 'desc' ? 'chevronDown' : 'chevronUp'} size={14} />
								</span>
								{#if sort?.key === c.key}
									<span class="sr">{sort.dir === 'asc' ? ut('ui.sortAscending') : ut('ui.sortDescending')}</span>
								{/if}
							</button>
						{:else}
							{c.label}
						{/if}
					</th>
				{/each}
			</tr>
		</thead>
		<tbody>
			{#if loading}
				<tr><td colspan={columns.length} class="state"><Skeleton shape="text" count={4} /></td></tr>
			{:else if rows.length === 0}
				<tr>
					<td colspan={columns.length} class="state">
						{#if empty}{@render empty()}{:else}<p class="none">{ut('ui.noRows')}</p>{/if}
					</td>
				</tr>
			{:else}
				{#each rows as row (rowKey(row))}
					{@const key = rowKey(row)}
					<tr
						class:selectable={!!onselect}
						class:selected={selected === key}
						aria-selected={onselect ? selected === key : undefined}
						tabindex={onselect ? 0 : undefined}
						onclick={onselect ? () => onselect(row) : undefined}
						onkeydown={onselect ? (e) => onrowkey(e, row) : undefined}
					>
						{#each columns as c (c.key)}
							<td class:end={c.align === 'end'} class:optional={c.optional} lang={c.lang}>
								{#if cell}{@render cell(row, c)}{:else}{text?.(row, c) ?? ''}{/if}
							</td>
						{/each}
					</tr>
				{/each}
			{/if}
		</tbody>
	</table>
</div>

<style>
	/* The table scrolls inside its own box; the page never scrolls sideways. */
	.wrap {
		container-type: inline-size;
		overflow-x: auto;
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
	}
	table {
		width: 100%;
		border-collapse: separate;
		border-spacing: 0;
		table-layout: fixed;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text);
	}
	th {
		position: sticky;
		top: 0;
		z-index: 1;
		height: 2.75rem;
		padding: 0 var(--kb-space-4);
		background: var(--kb-color-surface-sunken);
		border-bottom: 1px solid var(--kb-color-border);
		color: var(--kb-color-text-muted);
		font: var(--kb-type-label);
		text-align: start;
		white-space: nowrap;
	}
	td {
		height: 3.25rem;
		padding: var(--kb-space-2) var(--kb-space-4);
		border-bottom: 1px solid var(--kb-color-border);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	td:lang(ja) {
		font: var(--kb-type-body);
		font-weight: 500;
	}
	.compact td {
		height: 2.5rem;
		padding-block: var(--kb-space-1);
	}
	tbody tr:last-child td {
		border-bottom: 0;
	}
	.end {
		text-align: end;
		font-variant-numeric: tabular-nums;
	}
	th.end .sort {
		margin-inline-start: auto;
	}
	.sort {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
		min-height: var(--kb-size-touch-min);
		padding: 0;
		border: 0;
		background: none;
		color: inherit;
		font: inherit;
		cursor: pointer;
	}
	.sort:hover {
		color: var(--kb-color-text);
	}
	.sort:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
		border-radius: var(--kb-radius-xs);
	}
	.arrow {
		display: flex;
		opacity: 0;
	}
	.sort:hover .arrow,
	.arrow.active {
		opacity: 1;
	}
	.arrow.active {
		color: var(--kb-color-primary);
	}
	.selectable {
		cursor: pointer;
	}
	.selectable:hover td {
		background: var(--kb-color-surface-hover);
	}
	.selected td,
	.selected:hover td {
		background: var(--kb-color-surface-selected);
	}
	.selected td:first-child {
		box-shadow: inset 3px 0 0 var(--kb-color-primary);
	}
	tr:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: -2px;
	}
	.state {
		height: auto;
		padding: var(--kb-space-4);
		white-space: normal;
	}
	.none {
		margin: 0;
		padding: var(--kb-space-6);
		text-align: center;
		color: var(--kb-color-text-muted);
	}
	.sr {
		position: absolute;
		width: 1px;
		height: 1px;
		overflow: hidden;
		clip-path: inset(50%);
		white-space: nowrap;
	}
	@container (max-width: 599.98px) {
		.optional {
			display: none;
		}
	}
</style>
