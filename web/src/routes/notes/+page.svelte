<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import type { Note, NoteBaseType, NoteType } from '../../lib/api/generated';
	import { PAGE_SIZE } from '../../lib/cards/model';
	import { noteTypesApi, notesApi } from '../../lib/notes/api';
	import { HeadwordMap } from '../../lib/notes/headwords.svelte';
	import { tn } from '../../lib/notes/messages';
	import NoteEditor from '../../lib/notes/NoteEditor.svelte';
	import NoteTable from '../../lib/notes/NoteTable.svelte';

	const BASE_TYPES: NoteBaseType[] = ['vocab', 'kanji', 'phrase', 'grammar', 'sentence', 'custom'];

	let draft = $state('');
	let typeFilter = $state<NoteBaseType | ''>('');
	let tagFilter = $state('');
	let notes = $state<Note[]>([]);
	let cursor = $state<string | null>(null);
	let loading = $state(false);
	let loadedOnce = $state(false);
	let noteTypes = $state<Map<string, NoteType>>(new Map());
	let activeId = $state<string | null>(null);
	const headwords = new HeadwordMap();
	let generation = 0;
	let applied = { q: '', type: '' as NoteBaseType | '', tag: '' };

	const active = $derived(notes.find((n) => n.id === activeId) ?? null);

	async function loadPage(reset: boolean): Promise<void> {
		if (loading && !reset) return;
		if (!reset && cursor === null) return;
		const gen = reset ? ++generation : generation;
		loading = true;
		try {
			const res = await notesApi.listNotes({
				q: applied.q === '' ? undefined : applied.q,
				type: applied.type === '' ? undefined : applied.type,
				tag: applied.tag === '' ? undefined : applied.tag,
				limit: PAGE_SIZE,
				cursor: reset ? undefined : (cursor ?? undefined)
			});
			if (gen !== generation) return;
			notes = reset ? res.items : [...notes, ...res.items];
			cursor = res.nextCursor;
		} catch {
			if (gen !== generation) return;
			cursor = null;
		} finally {
			if (gen === generation) {
				loading = false;
				loadedOnce = true;
			}
		}
	}

	function search(): void {
		applied = { q: draft.trim(), type: typeFilter, tag: tagFilter.trim() };
		activeId = null;
		notes = [];
		cursor = null;
		void loadPage(true);
	}

	function activate(n: Note): void {
		activeId = n.id;
	}

	function saved(n: Note): void {
		notes = notes.map((x) => (x.id === n.id ? n : x));
	}

	function deleted(id: string): void {
		notes = notes.filter((x) => x.id !== id);
		activeId = null;
	}

	onMount(() => {
		void (async () => {
			try {
				noteTypes = new Map((await noteTypesApi.listNoteTypes({})).items.map((n) => [n.id, n]));
			} catch {
				/* toast shown; previews just report the missing type */
			}
			// Deep link from the dictionary page: /notes?note=<id>
			const wanted = page.url.searchParams.get('note');
			if (wanted) {
				try {
					const n = await notesApi.getNote({ noteId: wanted });
					if (!notes.some((x) => x.id === n.id)) notes = [n, ...notes];
					activate(n);
				} catch {
					/* toast shown */
				}
			}
		})();
		search();
	});
</script>

<h1>{tn('notes.title')}</h1>

<form
	class="search"
	onsubmit={(e) => {
		e.preventDefault();
		search();
	}}
>
	<label>
		{tn('notes.search')}
		<input type="search" bind:value={draft} lang="ja" />
	</label>
	<label>
		{tn('notes.type')}
		<select bind:value={typeFilter}>
			<option value="">{tn('notes.typeAny')}</option>
			{#each BASE_TYPES as b (b)}<option value={b}>{b}</option>{/each}
		</select>
	</label>
	<label>
		{tn('notes.tag')}
		<input type="text" bind:value={tagFilter} />
	</label>
	<button type="submit" class="kb-btn">{tn('notes.go')}</button>
</form>
<p class="muted">{tn('notes.searchHelp')}</p>

<div class="layout">
	<div class="main">
		{#if !loadedOnce}
			<p>{tn('notes.loading')}</p>
		{:else if notes.length === 0}
			<p>{tn('notes.none')}</p>
		{:else}
			<NoteTable
				{notes}
				{activeId}
				{headwords}
				hasMore={cursor !== null}
				onactivate={activate}
				onnearend={() => void loadPage(false)}
			/>
			<p class="muted">
				{tn('notes.loadedCount', { n: notes.length })}{#if cursor !== null}
					· {tn('notes.moreAvailable')}{/if}
			</p>
		{/if}
	</div>

	<aside>
		{#if active}
			<NoteEditor
				note={active}
				noteType={noteTypes.get(active.noteTypeId)}
				onentry={(seq, e) => headwords.put(seq, e)}
				onsaved={saved}
				ondeleted={deleted}
			/>
		{:else}
			<p class="muted">{tn('notes.selectPrompt')}</p>
		{/if}
	</aside>
</div>

<style>
	.search {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-3);
		align-items: end;
	}
	.search label {
		display: grid;
		gap: var(--kb-space-1);
	}
	.layout {
		display: grid;
		grid-template-columns: minmax(0, 2fr) minmax(16rem, 1fr);
		gap: var(--kb-space-4);
		margin-top: var(--kb-space-4);
		align-items: start;
	}
	.main {
		display: grid;
		gap: var(--kb-space-3);
		min-width: 0;
	}
	.muted {
		color: var(--kb-text-muted);
		margin: 0;
	}
	@media (max-width: 60rem) {
		.layout {
			grid-template-columns: 1fr;
		}
	}
</style>
