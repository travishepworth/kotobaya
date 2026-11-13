<script lang="ts">
	import { page } from '$app/state';
	import { apiConfig, API_BASE, ResponseError } from '../../../../lib/api/client';
	import { DecksApi, MediaApi, NotesApi } from '../../../../lib/api/generated';
	import type { Deck, MediaSegment, Sentence } from '../../../../lib/api/generated';
	import { formatMs, pieces } from '../../../../lib/reader/format';
	import { cachedStatus, loadStatus } from '../../../../lib/reader/status';
	import { uuidv7 } from '../../../../lib/review/model';
	import { buildSentenceNote, buildVocabNote, sentenceReading } from '../../../../lib/sentences/noteRequest';
	import { ts } from '../../../../lib/sentences/messages';
	import GrammarCapture from '../../../../lib/grammar-capture/GrammarCapture.svelte';
	import { tg } from '../../../../lib/grammar-capture/messages';
	import { pushToast } from '../../../../lib/toasts.svelte';

	type Filter = 'all' | 'i+1' | 'i+0';
	const media = new MediaApi(apiConfig);
	const notes = new NotesApi(apiConfig);
	const decksApi = new DecksApi(apiConfig);
	const mediaId = $derived(page.params.id ?? '');

	let filter = $state<Filter>('all');
	let segmentId = $state('');
	let q = $state('');
	let segments = $state<MediaSegment[]>([]);
	let decks = $state<Deck[]>([]);
	let rows = $state<Sentence[]>([]);
	let cursor = $state<string | null>(null);
	let loaded = $state(false);
	let unknown = $state<Record<string, number | undefined>>({});
	let gen = 0;

	// Card form state.
	let editing = $state<Sentence | null>(null);
	let capturing = $state<string | null>(null);
	let formSentence = $state('');
	let formReading = $state('');
	let formTranslation = $state('');
	let formDeck = $state('');
	let alsoVocab = $state(false);
	let noteId = $state(uuidv7());
	let vocabId = $state(uuidv7());

	$effect(() => {
		const id = mediaId;
		media
			.getMedia({ mediaId: id })
			.then((m) => {
				segments = m.segments ?? [];
			})
			.catch(() => {});
		decksApi
			.listDecks({ type: 'user' })
			.then((d) => {
				decks = d.items;
			})
			.catch(() => {});
	});

	async function findUnknown(s: Sentence): Promise<void> {
		for (const t of s.tokens) {
			if (t.seq == null || t._class !== 'content') continue;
			const st = cachedStatus(t.seq) ?? (await loadStatus(t.seq));
			if (st === 'new') {
				unknown[s.id] = t.seq;
				return;
			}
		}
	}

	async function load(more: boolean): Promise<void> {
		const mine = more ? gen : ++gen;
		try {
			const p = await media.listMediaSentences({
				mediaId,
				// The generated client URL-encodes the query value, so "i+1" goes out as i%2B1.
				filter,
				segmentId: segmentId || undefined,
				q: q.trim() || undefined,
				limit: 50,
				cursor: more ? (cursor ?? undefined) : undefined
			});
			if (mine !== gen) return;
			rows = more ? [...rows, ...p.items] : p.items;
			cursor = p.nextCursor;
			for (const s of p.items) void findUnknown(s);
		} catch {
			/* toast shown */
		} finally {
			if (mine === gen) loaded = true;
		}
	}

	$effect(() => {
		void mediaId;
		void filter;
		void segmentId;
		void q;
		loaded = false;
		const h = setTimeout(() => void load(false), 250);
		return () => clearTimeout(h);
	});

	function open(s: Sentence): void {
		editing = s;
		formSentence = s.text;
		formReading = sentenceReading(s);
		formTranslation = '';
		formDeck = '';
		alsoVocab = false;
		noteId = uuidv7();
		vocabId = uuidv7();
	}

	async function create(): Promise<void> {
		const s = editing;
		if (!s) return;
		const edited = { ...s, text: formSentence };
		try {
			await notes.createNote({
				noteCreate: buildSentenceNote(edited, {
					id: noteId,
					reading: formReading,
					translation: formTranslation,
					deckId: formDeck || undefined
				})
			});
			pushToast(ts('sn.created'), 'info');
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 409) {
				pushToast(ts('sn.duplicate'), 'info');
			} else return;
		}
		const seq = unknown[s.id];
		if (alsoVocab && seq != null) {
			try {
				await notes.createNote({
					noteCreate: buildVocabNote(s, seq, vocabId, formDeck || undefined)
				});
				pushToast(ts('sn.vocabCreated'), 'info');
			} catch (e) {
				if (e instanceof ResponseError && e.response.status === 409) {
					pushToast(ts('sn.duplicate'), 'info');
				}
			}
		}
		editing = null;
	}

	function play(hash: string): void {
		void new Audio(`${API_BASE}/blobs/${hash}`).play();
	}
</script>

<h1>{ts('sn.title')}</h1>

<div class="controls">
	<label>
		{ts('sn.filter')}
		<select bind:value={filter}>
			<option value="all">{ts('sn.filter.all')}</option>
			<option value="i+1">{ts('sn.filter.i1')}</option>
			<option value="i+0">{ts('sn.filter.i0')}</option>
		</select>
	</label>
	<label>
		{ts('sn.segment')}
		<select bind:value={segmentId}>
			<option value="">{ts('sn.allSegments')}</option>
			{#each segments as s (s.id)}
				<option value={s.id}>{s.title || String(s.ordinal + 1)}</option>
			{/each}
		</select>
	</label>
	<input type="search" placeholder={ts('sn.search')} aria-label={ts('sn.search')} bind:value={q} />
</div>

{#if !loaded}
	<p>{ts('sn.loading')}</p>
{:else if rows.length === 0}
	<p>{ts('sn.empty')}</p>
{/if}

<ul class="rows" lang="ja">
	{#each rows as s (s.id)}
		<li>
			<span class="sentence">
				{#each pieces(s) as piece, i (i)}
					{#if piece.tokenIndex !== null && s.tokens[piece.tokenIndex].seq != null && s.tokens[piece.tokenIndex].seq === unknown[s.id]}
						<mark>{piece.text}</mark>
					{:else}{piece.text}{/if}
				{/each}
			</span>
			{#if s.startMs != null}<small>{ts('sn.at', { time: formatMs(s.startMs) })}</small>{/if}
			{#if s.audioBlobHash}
				{@const hash = s.audioBlobHash}
				<button type="button" onclick={() => play(hash)}>{ts('sn.play')}</button>
			{/if}
			<button type="button" onclick={() => open(s)}>{ts('sn.make')}</button>
			<button type="button" onclick={() => (capturing = capturing === s.id ? null : s.id)}>{tg('gc.open')}</button>
			{#if capturing === s.id}<GrammarCapture sentence={s} onclose={() => (capturing = null)} />{/if}
			{#if editing?.id === s.id}
				<form
					class="card-form"
					onsubmit={(e) => {
						e.preventDefault();
						void create();
					}}
				>
					<label>{ts('sn.form.sentence')} <input bind:value={formSentence} /></label>
					<label>{ts('sn.form.reading')} <input bind:value={formReading} /></label>
					<label>{ts('sn.form.translation')} <input bind:value={formTranslation} /></label>
					<label>
						{ts('sn.form.deck')}
						<select bind:value={formDeck}>
							<option value="">{ts('sn.form.defaultDeck')}</option>
							{#each decks as d (d.id)}<option value={d.id}>{d.name}</option>{/each}
						</select>
					</label>
					{#if unknown[s.id] != null}
						<label><input type="checkbox" bind:checked={alsoVocab} /> {ts('sn.form.alsoVocab')}</label>
					{/if}
					<button type="submit">{ts('sn.form.create')}</button>
					<button type="button" onclick={() => (editing = null)}>{ts('sn.form.cancel')}</button>
				</form>
			{/if}
		</li>
	{/each}
</ul>

{#if cursor}<button type="button" onclick={() => load(true)}>{ts('sn.more')}</button>{/if}

<style>
	.controls {
		display: flex;
		gap: var(--kb-space-3);
		margin-bottom: var(--kb-space-3);
		flex-wrap: wrap;
	}
	.rows {
		list-style: none;
		padding: 0;
	}
	.rows li {
		margin-bottom: var(--kb-space-3);
	}
	.sentence {
		font-size: 1.15rem;
	}
	.card-form {
		display: grid;
		gap: var(--kb-space-2);
		margin-top: var(--kb-space-2);
	}
</style>
