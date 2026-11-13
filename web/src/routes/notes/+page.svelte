<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { tn } from '../../lib/notes/messages';
	import NoteEditor from '../../lib/notes/NoteEditor.svelte';
	import NoteTable from '../../lib/notes/NoteTable.svelte';
	import { BASE_TYPES, NotesPage } from '../../lib/state/notes.svelte';

	const s = new NotesPage();

	// Deep link from the dictionary page: /notes?note=<id>
	onMount(() => s.start(page.url.searchParams.get('note')));
</script>

<h1>{tn('notes.title')}</h1>

<form
	class="search"
	onsubmit={(e) => {
		e.preventDefault();
		s.search();
	}}
>
	<label>
		{tn('notes.search')}
		<input type="search" bind:value={s.draft} lang="ja" />
	</label>
	<label>
		{tn('notes.type')}
		<select bind:value={s.typeFilter}>
			<option value="">{tn('notes.typeAny')}</option>
			{#each BASE_TYPES as b (b)}<option value={b}>{b}</option>{/each}
		</select>
	</label>
	<label>
		{tn('notes.tag')}
		<input type="text" bind:value={s.tagFilter} />
	</label>
	<button type="submit" class="kb-btn">{tn('notes.go')}</button>
</form>
<p class="muted">{tn('notes.searchHelp')}</p>

<div class="layout">
	<div class="main">
		{#if !s.loadedOnce}
			<p>{tn('notes.loading')}</p>
		{:else if s.notes.length === 0}
			<p>{tn('notes.none')}</p>
		{:else}
			<NoteTable
				notes={s.notes}
				activeId={s.activeId}
				headwords={s.headwords}
				hasMore={s.cursor !== null}
				onactivate={(n) => s.activate(n)}
				onnearend={() => void s.loadPage(false)}
			/>
			<p class="muted">
				{tn('notes.loadedCount', { n: s.notes.length })}{#if s.cursor !== null}
					· {tn('notes.moreAvailable')}{/if}
			</p>
		{/if}
	</div>

	<aside>
		{#if s.active}
			<NoteEditor
				note={s.active}
				noteType={s.noteTypes.get(s.active.noteTypeId)}
				onentry={(seq, e) => s.entryLoaded(seq, e)}
				onsaved={(n) => s.saved(n)}
				ondeleted={(id) => s.deleted(id)}
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
