<script lang="ts">
	// Browse › Notes: search and filter notes, edit one next to its live card (FEAT-x per-field overrides).
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import Button from '../../lib/ui/Button.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import Select from '../../lib/ui/Select.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import VirtualTable from '../../lib/ui/VirtualTable.svelte';
	import type { TableColumn } from '../../lib/ui/kinds';
	import { tn } from '../../lib/notes/messages';
	import { BASE_TYPES, NotesPage, rowLabel, type Note, type NoteBaseType } from '../../lib/state/notes.svelte';
	import BrowseLayout from '../cards/BrowseLayout.svelte';
	import { tb, type BrowseKey } from '../cards/messages';
	import NoteEditor from './NoteEditor.svelte';

	const s = new NotesPage();
	// Deep link from the dictionary: /notes?note=<id>
	onMount(() => s.start(page.url.searchParams.get('note')));

	const headwordMap = $derived(new Map([...s.headwords.byseq].map(([k, v]) => [k, v.headword])));
	const typeOptions = [
		{ value: '' as NoteBaseType | '', label: tb('br.typeAny') },
		...BASE_TYPES.map((b) => ({ value: b as NoteBaseType | '', label: tb(`br.type.${b}` as BrowseKey) }))
	];
	const columns: TableColumn[] = [
		{ key: 'note', label: tn('notes.col.note'), lang: 'ja' },
		{ key: 'meaning', label: tn('notes.col.meaning'), optional: true },
		{ key: 'type', label: tb('br.col.type'), width: '8rem', optional: true },
		{ key: 'cards', label: tb('br.col.cards'), width: '5rem', align: 'end', optional: true }
	];

	// Dictionary-backed notes are named by their headword; others by their first field.
	function label(n: Note): string {
		const hw = n.entrySeq != null ? headwordMap.get(n.entrySeq) : undefined;
		return hw ?? rowLabel(n, n.id, headwordMap);
	}
	function meaning(n: Note): string {
		const hw = n.entrySeq != null ? s.headwords.get(n.entrySeq) : undefined;
		return hw?.gloss ?? '';
	}
	function submit(e: Event): void {
		e.preventDefault();
		s.search();
	}
	function clear(): void {
		s.draft = '';
		s.typeFilter = '';
		s.tagFilter = '';
		s.search();
	}
	const filtered = $derived(s.draft.trim() !== '' || s.typeFilter !== '' || s.tagFilter.trim() !== '');
</script>

<svelte:head><title>{tb('br.notes')} · Kotobaya</title></svelte:head>

{#snippet toolbar()}
	<form class="tools" onsubmit={submit}>
		<div class="q">
			<TextField
				label={tb('br.noteSearch')}
				hideLabel
				type="search"
				icon="search"
				clearable
				lang="ja"
				placeholder={tb('br.noteSearchPlaceholder')}
				bind:value={s.draft}
			/>
		</div>
		<div class="type"><Select label={tb('br.type')} hideLabel options={typeOptions} bind:value={s.typeFilter} onchange={() => s.search()} /></div>
		<div class="tag"><TextField label={tb('br.tag')} hideLabel icon="flag" placeholder={tb('br.tag')} bind:value={s.tagFilter} /></div>
		<Button variant="primary" type="submit">{tb('br.search')}</Button>
	</form>
{/snippet}

{#snippet cell(n: Note, col: TableColumn)}
	{#if col.key === 'note'}
		<button type="button" class="link" onclick={() => s.activate(n)}>{label(n)}</button>
	{:else if col.key === 'meaning'}
		<span class="muted">{meaning(n)}</span>
	{:else if col.key === 'type'}
		<span class="muted">{tb(`br.type.${n.type}` as BrowseKey)}</span>
	{:else}
		<span class="kb-num">{n.cards.length}</span>
	{/if}
{/snippet}

{#snippet master()}
	<VirtualTable
		label={tb('br.notes')}
		{columns}
		rows={s.notes}
		rowKey={(n) => n.id}
		{cell}
		activeKey={s.activeId}
		onactivate={(n) => s.activate(n)}
		loading={!s.loadedOnce || (s.loading && s.notes.length === 0)}
		hasMore={s.cursor !== null}
		onnearend={() => void s.loadPage(false)}
		height="min(40rem, calc(100dvh - 14rem))"
	>
		{#snippet empty()}
			{#if filtered}
				<EmptyState art="search" title={tb('br.noNotesTitle')} body={tb('br.noneBody')}>
					{#snippet action()}<Button variant="outline" onclick={clear}>{tb('br.clearSearch')}</Button>{/snippet}
				</EmptyState>
			{:else}
				<EmptyState art="edit" title={tb('br.emptyNotesTitle')} body={tb('br.emptyBody')}>
					{#snippet action()}<Button variant="tonal" icon="search" href="/dictionary">{tb('br.toDictionary')}</Button>{/snippet}
				</EmptyState>
			{/if}
		{/snippet}
	</VirtualTable>
	{#if s.notes.length > 0}
		<p class="count">{s.cursor !== null ? tb('br.loadedMore', { n: s.notes.length }) : tb('br.loaded', { n: s.notes.length })}</p>
	{/if}
{/snippet}

{#snippet detail()}
	{#if s.active}
		<NoteEditor
			note={s.active}
			noteType={s.noteTypes.get(s.active.noteTypeId)}
			onentry={(seq, e) => s.entryLoaded(seq, e)}
			onsaved={(n) => s.saved(n)}
			ondeleted={(id) => s.deleted(id)}
		/>
	{/if}
{/snippet}

{#snippet placeholder()}
	<EmptyState art="edit" title={tb('br.pickNote')} body={tb('br.pickNoteBody')} />
{/snippet}

<BrowseLayout
	view="notes"
	{toolbar}
	{master}
	detail={s.active ? detail : null}
	{placeholder}
	detailTitle={s.active ? label(s.active) : tn('notes.edit')}
	onclose={() => (s.activeId = null)}
/>

<style>
	.tools {
		display: flex;
		flex-wrap: wrap;
		align-items: flex-start;
		gap: var(--kb-space-2);
	}
	.q {
		flex: 1 1 16rem;
		min-width: 0;
	}
	.type {
		flex: 1 1 9rem;
		min-width: 0;
		max-width: 12rem;
	}
	.tag {
		flex: 1 1 8rem;
		min-width: 0;
		max-width: 11rem;
	}
	.link {
		max-width: 100%;
		padding: 0;
		border: 0;
		background: none;
		color: inherit;
		font: inherit;
		text-align: start;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		cursor: pointer;
	}
	.link:focus-visible {
		outline: 2px solid var(--kb-color-focus);
	}
	.muted {
		color: var(--kb-color-text-muted);
	}
	.count {
		margin-top: var(--kb-space-2);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
</style>
