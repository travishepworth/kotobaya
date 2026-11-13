<script lang="ts">
	// Preview pane: renders the card through the shared core (renderCard) into the shared CardView.
	import type { Card, DictEntry, Kanji, NoteType } from '../api/generated';
	import { renderCard, type CardSide, type RenderedCard } from '../core/index';
	import CardView from '../review/CardView.svelte';
	import { MEDIA_URL_TEMPLATE, toCoreNoteType, toDictionary, toKanji } from '../review/model';
	import { fetchEntry, fetchKanji, fetchNoteKinds } from './api';
	import { tc } from './messages';

	let {
		card,
		noteType,
		onheadword
	}: { card: Card; noteType: NoteType | undefined; onheadword?: (seq: number, headword: string) => void } = $props();

	let entry = $state<DictEntry | null>(null);
	let kanji = $state<Kanji | null>(null);
	let loading = $state(false);
	let failed = $state(false);

	// Referenced notes render from the dictionary: fetch (cached) whenever the card changes.
	$effect(() => {
		const seq = card.note?.entrySeq ?? null;
		const lit = card.note?.kanjiLiteral ?? null;
		entry = null;
		kanji = null;
		failed = false;
		loading = seq !== null || lit !== null;
		if (!loading) return;
		let stale = false;
		void (async () => {
			try {
				if (seq !== null) {
					const e = await fetchEntry(seq);
					if (stale) return;
					entry = e;
					onheadword?.(seq, e.headword);
				}
				if (lit !== null) {
					const k = await fetchKanji(lit);
					if (stale) return;
					kanji = k;
				}
			} catch {
				if (!stale) failed = true;
			} finally {
				if (!stale) loading = false;
			}
		})();
		return () => {
			stale = true;
		};
	});

	let side = $state<CardSide>('front');
	let kind = $state('');
	let noteKinds = $state<string[]>([]);

	// A newly chosen card starts on its front, on the kind that was clicked.
	$effect(() => {
		kind = card.kind;
		side = 'front';
		noteKinds = [];
		let stale = false;
		fetchNoteKinds(card.noteId).then(
			(ks) => {
				if (!stale) noteKinds = ks;
			},
			() => {
				/* switcher just shows the clicked card */
			}
		);
		return () => {
			stale = true;
		};
	});

	/** The note's live card kinds, in note-type template order; the clicked card's kind is always included. */
	const kinds = $derived.by(() => {
		const live = new Set(noteKinds);
		live.add(card.kind);
		const order = noteType ? Object.keys(noteType.templates) : [];
		return [...live].sort((a, b) => {
			const ia = order.indexOf(a);
			const ib = order.indexOf(b);
			return (ia < 0 ? order.length : ia) - (ib < 0 ? order.length : ib);
		});
	});
	const activeKind = $derived(kinds.includes(kind) ? kind : card.kind);

	type Rendered = { ok: true; card: RenderedCard } | { ok: false; detail: string };

	const rendered = $derived.by((): Rendered => {
		const note = card.note;
		if (!note || !noteType) return { ok: false, detail: tc('cards.previewMissingType') };
		try {
			return {
				ok: true,
				card: renderCard({
					noteType: toCoreNoteType(noteType),
					kind: activeKind,
					fields: note.fields,
					mnemonic: note.mnemonic,
					dictionary: toDictionary(entry),
					kanji: toKanji(kanji),
					side,
					options: { furigana: true, pitch: true, showMnemonic: true, mediaUrlTemplate: MEDIA_URL_TEMPLATE }
				})
			};
		} catch (e) {
			return { ok: false, detail: e instanceof Error ? e.message : String(e) };
		}
	});
</script>

<section aria-label={tc('cards.preview')}>
	{#if kinds.length > 1}
		<div class="tabs">
			{#each kinds as k (k)}
				<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={activeKind === k} onclick={() => (kind = k)}>
					{k}
				</button>
			{/each}
		</div>
	{/if}
	<div class="tabs">
		<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={side === 'front'} onclick={() => (side = 'front')}>
			{tc('cards.previewFront')}
		</button>
		<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={side === 'back'} onclick={() => (side = 'back')}>
			{tc('cards.previewBack')}
		</button>
	</div>
	{#if loading}
		<p class="muted">{tc('cards.previewLoading')}</p>
	{:else if failed}
		<p class="err">{tc('cards.previewDictFailed')}</p>
	{/if}
	{#if rendered.ok}
		<CardView rendered={rendered.card} />
	{:else}
		<p class="err">{tc('cards.previewFailed', { detail: rendered.detail })}</p>
	{/if}
</section>

<style>
	.tabs {
		display: flex;
		gap: var(--kb-space-2);
		margin-bottom: var(--kb-space-2);
	}
	[aria-pressed='true'] {
		font-weight: 700;
	}
	.muted {
		color: var(--kb-text-muted);
	}
	.err {
		color: var(--kb-error);
	}
</style>
