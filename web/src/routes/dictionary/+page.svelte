<script lang="ts">
	import { onMount } from 'svelte';
	import type { DictEntry, DictEntrySummary, EntryExamples } from '../../lib/api/generated';
	import { dictionaryApi, noteTypesApi, notesApi } from '../../lib/dictionary/api';
	import { td } from '../../lib/dictionary/messages';
	import { pushToast } from '../../lib/toasts.svelte';

	const PAGE = 50;
	const DEBOUNCE_MS = 300;

	let query = $state('');
	let commonOnly = $state(false);
	let items = $state<DictEntrySummary[]>([]);
	let cursor = $state<string | null>(null);
	let searching = $state(false);
	let searched = $state(false);
	let selectedSeq = $state<number | null>(null);
	let entry = $state<DictEntry | null>(null);
	let examples = $state<EntryExamples | null>(null);
	let loadingEntry = $state(false);
	let adding = $state(false);
	let searchGen = 0;
	let entryGen = 0;
	let timer: ReturnType<typeof setTimeout> | undefined;

	async function runSearch(reset: boolean): Promise<void> {
		const q = query.trim();
		const gen = reset ? ++searchGen : searchGen;
		if (reset) {
			items = [];
			cursor = null;
			if (q === '') {
				searching = false;
				searched = false;
				return;
			}
		} else if (cursor === null) return;
		searching = true;
		try {
			const page = await dictionaryApi.searchDictionary({
				q,
				commonOnly,
				limit: PAGE,
				cursor: reset ? undefined : (cursor ?? undefined)
			});
			if (gen !== searchGen) return;
			items = reset ? page.items : [...items, ...page.items];
			cursor = page.nextCursor;
			searched = true;
		} catch {
			if (gen !== searchGen) return;
			cursor = null;
			searched = true;
		} finally {
			if (gen === searchGen) searching = false;
		}
	}

	function onInput(): void {
		clearTimeout(timer);
		timer = setTimeout(() => void runSearch(true), DEBOUNCE_MS);
	}

	function submit(e: Event): void {
		e.preventDefault();
		clearTimeout(timer);
		void runSearch(true);
	}

	async function open(seq: number): Promise<void> {
		selectedSeq = seq;
		const gen = ++entryGen;
		loadingEntry = true;
		entry = null;
		examples = null;
		try {
			const [e, ex] = await Promise.all([
				dictionaryApi.getDictEntry({ seq }),
				dictionaryApi.listEntryExamples({ seq, limit: 10 })
			]);
			if (gen !== entryGen) return;
			entry = e;
			examples = ex;
		} catch {
			/* error toast shown by the client */
		} finally {
			if (gen === entryGen) loadingEntry = false;
		}
	}

	async function addNote(e: DictEntry): Promise<void> {
		adding = true;
		try {
			const types = await noteTypesApi.listNoteTypes({});
			const vocab = types.items.find((n) => n.baseType === 'vocab' && n.deletedAt === null);
			if (!vocab) {
				pushToast(td('dict.noVocabType'), 'error');
				return;
			}
			const note = await notesApi.createNote({
				noteCreate: { noteTypeId: vocab.id, entrySeq: e.seq, origin: 'manual' }
			});
			pushToast(td('dict.added'), 'info');
			entry = { ...e, noteId: note.id };
			items = items.map((i) => (i.seq === e.seq ? { ...i, noteId: note.id } : i));
		} catch {
			/* error toast shown by the client */
		} finally {
			adding = false;
		}
	}

	function pitchText(positions: number[]): string {
		return positions
			.map((p) => (p === 0 ? td('dict.pitchHeiban') : td('dict.pitchDrop', { n: p })))
			.join(', ');
	}

	onMount(() => () => clearTimeout(timer));
</script>

<h1>{td('dict.title')}</h1>

<form class="search" onsubmit={submit}>
	<label>
		{td('dict.search')}
		<input type="search" bind:value={query} oninput={onInput} lang="ja" />
	</label>
	<label class="inline">
		<input type="checkbox" bind:checked={commonOnly} onchange={() => void runSearch(true)} />
		{td('dict.commonOnly')}
	</label>
	<p class="muted">{td('dict.searchHelp')}</p>
</form>

<div class="layout">
	<section aria-label={td('dict.results')}>
		{#if searching && items.length === 0}
			<p>{td('dict.searching')}</p>
		{:else if !searched}
			<p class="muted">{td('dict.empty')}</p>
		{:else if items.length === 0}
			<p>{td('dict.none')}</p>
		{:else}
			<ul class="results">
				{#each items as it (it.seq)}
					<li>
						<button
							type="button"
							class="result"
							aria-current={it.seq === selectedSeq ? 'true' : undefined}
							onclick={() => void open(it.seq)}
						>
							<span class="hw" lang="ja">{it.headword}</span>
							{#if it.headword !== it.headwordReading}
								<span class="rd" lang="ja">{it.headwordReading}</span>
							{/if}
							<span class="gloss">{it.glosses.join('; ')}</span>
							<span class="meta">
								{#if it.isCommon}{td('dict.common')}{/if}
								{#if it.freqRank !== null}
									{td('dict.rank', { n: it.freqRank })}{/if}
								{#if it.retired}{td('dict.retired')}{/if}
								{#if it.noteId !== null}{td('dict.hasNote')}{/if}
							</span>
						</button>
					</li>
				{/each}
			</ul>
			{#if cursor !== null}
				<button
					type="button"
					class="kb-btn"
					disabled={searching}
					onclick={() => void runSearch(false)}
				>
					{td('dict.more')}
				</button>
			{/if}
		{/if}
	</section>

	<aside aria-label={td('dict.entry')}>
		{#if loadingEntry}
			<p>{td('dict.loadingEntry')}</p>
		{:else if entry}
			{@const e = entry}
			<h2 lang="ja">{e.headword}</h2>
			<p class="meta">
				{e.pos.join(', ')}
				{#if e.isCommon}· {td('dict.common')}{/if}
				{#if e.retired}· {td('dict.retired')}{/if}
				· {td('dict.status', { status: e.status })}
			</p>
			{#if e.noteId === null}
				<button type="button" class="kb-btn" disabled={adding} onclick={() => void addNote(e)}>
					{adding ? td('dict.adding') : td('dict.addNote')}
				</button>
			{:else}
				<a href="/notes?note={e.noteId}">{td('dict.openNotes')}</a>
			{/if}

			{#if e.kanjiForms.length > 0}
				<h3>{td('dict.kanjiForms')}</h3>
				<p lang="ja">{e.kanjiForms.map((k) => k.text).join('、')}</p>
			{/if}
			<h3>{td('dict.readings')}</h3>
			<p lang="ja">{e.readings.map((r) => r.text).join('、')}</p>
			{#if e.pitch.length > 0}
				<h3>{td('dict.pitch')}</h3>
				<ul>
					{#each e.pitch as p (p.reading)}
						<li><span lang="ja">{p.reading}</span>: {pitchText(p.positions)}</li>
					{/each}
				</ul>
			{/if}
			{#if e.freqs.length > 0}
				<h3>{td('dict.frequency')}</h3>
				<ul>
					{#each e.freqs as f (f.listId)}
						<li>{f.listId}: #{f.rank}</li>
					{/each}
				</ul>
			{/if}
			<h3>{td('dict.senses')}</h3>
			<ol>
				{#each e.senses as s, i (i)}
					<li>
						<span class="meta">{[...s.pos, ...s.misc, ...s.field, ...s.dialect].join(', ')}</span>
						<div>{s.gloss.map((g) => g.text).join('; ')}</div>
						{#if s.info.length > 0}<div class="muted">{s.info.join('; ')}</div>{/if}
					</li>
				{/each}
			</ol>
			{#if e.kanji.length > 0}
				<h3>{td('dict.kanji')}</h3>
				<p lang="ja">{e.kanji.join(' ')}</p>
			{/if}
			<h3>{td('dict.examples')}</h3>
			{#if examples && (examples.media.length > 0 || examples.tatoeba.length > 0)}
				{#if examples.media.length > 0}
					<h4>{td('dict.examplesMedia')}</h4>
					<ul>
						{#each examples.media as s (s.id)}<li lang="ja">{s.text}</li>{/each}
					</ul>
				{/if}
				{#if examples.tatoeba.length > 0}
					<h4>{td('dict.examplesTatoeba')}</h4>
					<ul>
						{#each examples.tatoeba as s (s.id)}
							<li>
								<span lang="ja">{s.text}</span>{#if s.translation}<br /><span class="muted"
										>{s.translation}</span
									>{/if}
							</li>
						{/each}
					</ul>
				{/if}
			{:else}
				<p class="muted">{td('dict.examplesNone')}</p>
			{/if}
		{:else}
			<p class="muted">{td('dict.selectPrompt')}</p>
		{/if}
	</aside>
</div>

<style>
	.search {
		display: grid;
		gap: var(--kb-space-2);
		max-width: 32rem;
	}
	.inline {
		display: flex;
		gap: var(--kb-space-2);
		align-items: center;
	}
	.layout {
		display: grid;
		grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
		gap: var(--kb-space-4);
		margin-top: var(--kb-space-4);
		align-items: start;
	}
	.results {
		list-style: none;
		margin: 0 0 var(--kb-space-2);
		padding: 0;
		border: 1px solid var(--kb-border);
		border-radius: var(--kb-radius);
	}
	.result {
		all: unset;
		box-sizing: border-box;
		display: grid;
		grid-template-columns: auto auto 1fr;
		gap: var(--kb-space-2);
		width: 100%;
		padding: var(--kb-space-2);
		cursor: pointer;
	}
	.result[aria-current='true'] {
		background: var(--kb-info-bg);
	}
	.result:focus-visible {
		outline: 2px solid var(--kb-focus);
	}
	.hw {
		font-weight: 700;
	}
	.meta {
		grid-column: 1 / -1;
		color: var(--kb-text-muted);
		font-size: 0.85rem;
	}
	aside .meta {
		margin: 0;
	}
	.muted {
		color: var(--kb-text-muted);
		margin: 0;
	}
	h3,
	h4 {
		margin-bottom: 0;
	}
	@media (max-width: 60rem) {
		.layout {
			grid-template-columns: 1fr;
		}
	}
</style>
