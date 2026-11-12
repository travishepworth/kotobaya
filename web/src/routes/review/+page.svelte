<script lang="ts">
	import { page } from '$app/state';
	import type {
		Note,
		NoteType,
		Preset,
		QueueItem,
		ReviewQueueCounts,
		DrillSessionRequestSourceEnum
	} from '../../lib/api/generated';
	import { ResponseError } from '../../lib/api/client';
	import { previewIntervals, renderCard, type IntervalPreview, type RenderedCard } from '../../lib/core/index';
	import { pushToast } from '../../lib/toasts.svelte';
	import { cardsApi, noteTypesApi, presetsApi, reviewApi, settingsApi, wordsApi } from '../../lib/review/api';
	import CardView from '../../lib/review/CardView.svelte';
	import NoteEditForm from '../../lib/review/NoteEditForm.svelte';
	import { tr, type ReviewMessageKey } from '../../lib/review/messages';
	import {
		BUTTONS_4,
		BUTTONS_5,
		MEDIA_URL_TEMPLATE,
		formatInterval,
		toCoreNoteType,
		toCorePreset,
		toDictionary,
		toKanji,
		toSchedule,
		uuidv7,
		type GradeButton
	} from '../../lib/review/model';

	const QUEUE_LIMIT = 20;

	let loaded = $state(false);
	let items = $state<QueueItem[]>([]);
	let counts = $state<ReviewQueueCounts | null>(null);
	let dayEndsAt = $state<Date | null>(null);
	let noteTypes = $state<Map<string, NoteType>>(new Map());
	let presets = $state<Map<string, Preset>>(new Map());
	let fiveButtons = $state(false);
	let furigana = $state(true);
	let flipped = $state(false);
	let editing = $state(false);
	let busy = $state(false);
	let drillUnavailable = $state(false);
	let answerId = $state(uuidv7());
	let shownAt = Date.now();
	/** Answered reviews of this session, newest last (for undo). In drill mode the item comes back. */
	let history = $state<{ reviewId: string; item: QueueItem }[]>([]);
	let cardView: CardView | undefined = $state();

	const drillSource = $derived(page.url.searchParams.get('drill'));
	const isDrill = $derived(drillSource !== null);
	const buttons = $derived<GradeButton[]>(fiveButtons ? BUTTONS_5 : BUTTONS_4);
	const current = $derived<QueueItem | undefined>(items[0]);
	const utcOffsetMin = -new Date().getTimezoneOffset();

	// A new card on screen: fresh answer id (reused on retry so a double submit cannot double-log).
	let lastCardId = '';
	$effect(() => {
		const id = current?.card.id ?? '';
		if (id !== lastCardId) {
			lastCardId = id;
			answerId = uuidv7();
			flipped = false;
			editing = false;
			shownAt = Date.now();
		}
	});

	type Rendered = { ok: true; card: RenderedCard } | { ok: false; detail: string };

	const rendered = $derived.by((): Rendered | null => {
		const item = current;
		if (!item) return null;
		const note = item.card.note;
		const nt = note ? noteTypes.get(note.noteTypeId) : undefined;
		if (!note || !nt) return { ok: false, detail: tr('review.missingNoteType') };
		try {
			return {
				ok: true,
				card: renderCard({
					noteType: toCoreNoteType(nt),
					kind: item.card.kind,
					fields: note.fields,
					mnemonic: note.mnemonic,
					dictionary: toDictionary(item.entry),
					kanji: toKanji(item.kanji),
					side: flipped ? 'back' : 'front',
					// Furigana is switched with a CSS class in CardView, not by re-rendering.
					options: { furigana: true, pitch: true, showMnemonic: true, mediaUrlTemplate: MEDIA_URL_TEMPLATE }
				})
			};
		} catch (e) {
			return { ok: false, detail: e instanceof Error ? e.message : String(e) };
		}
	});

	const previews = $derived.by((): IntervalPreview[] | null => {
		const item = current;
		const preset = item ? presets.get(item.card.presetId) : undefined;
		if (!item || !preset) return null;
		try {
			return previewIntervals(toSchedule(item.card), toCorePreset(preset), Date.now(), {
				reviewId: answerId,
				utcOffsetMin
			});
		} catch {
			return null;
		}
	});

	function previewText(b: GradeButton): string {
		const p = previews?.find((x) => x.rating === b.rating);
		return p ? formatInterval(p.intervalMs) : '';
	}

	/* ---------- loading ---------- */

	async function loadStatic(): Promise<void> {
		const [nts, ps, st] = await Promise.all([
			noteTypesApi.listNoteTypes(),
			presetsApi.listPresets(),
			settingsApi.listSettings()
		]);
		noteTypes = new Map(nts.items.map((n) => [n.id, n]));
		presets = new Map(ps.items.map((p) => [p.id, p]));
		fiveButtons = st.items.find((s) => s.key === 'scheduler.grading_buttons')?.value === 5;
	}

	async function fetchQueue(): Promise<void> {
		if (isDrill) return;
		const q = await reviewApi.getReviewQueue({ limit: QUEUE_LIMIT });
		items = q.items;
		counts = q.counts;
		dayEndsAt = q.dayEndsAt;
	}

	async function fetchDrill(source: string): Promise<void> {
		try {
			const s = await reviewApi.createDrillSession({
				drillSessionRequest: { source: source as DrillSessionRequestSourceEnum, mode: 'drill' }
			});
			items = s.items;
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 501) drillUnavailable = true;
		}
	}

	$effect(() => {
		const source = drillSource;
		void (async () => {
			try {
				await loadStatic();
				if (source !== null) await fetchDrill(source);
				else await fetchQueue();
			} catch {
				/* toast shown */
			} finally {
				loaded = true;
			}
		})();
	});

	async function reload(): Promise<void> {
		busy = true;
		try {
			await fetchQueue();
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}

	/* ---------- actions ---------- */

	/** Remove the current card locally (drill) or refetch the queue (scheduled). */
	async function advance(): Promise<void> {
		if (isDrill) items = items.slice(1);
		else await fetchQueue();
	}

	async function grade(b: GradeButton): Promise<void> {
		const item = current;
		if (!item || !flipped || busy || editing) return;
		busy = true;
		try {
			const r = await reviewApi.answerReview({
				reviewAnswerRequest: {
					id: answerId,
					cardId: item.card.id,
					rating: b.rating,
					...(b.button ? { button: b.button } : {}),
					mode: item.reviewType === 'drill' || item.reviewType === 'cram' ? item.reviewType : 'schedule',
					durationMs: Math.max(0, Date.now() - shownAt),
					utcOffsetMin
				}
			});
			history = [...history, { reviewId: r.review.id, item }];
			await advance();
		} catch {
			/* toast shown; answerId is kept so a retry is idempotent */
		} finally {
			busy = false;
		}
	}

	async function undo(): Promise<void> {
		const last = history[history.length - 1];
		if (!last) {
			pushToast(tr('review.nothingToUndo'));
			return;
		}
		if (busy) return;
		busy = true;
		try {
			await reviewApi.undoReview({ reviewUndoRequest: { reviewId: last.reviewId } });
			history = history.slice(0, -1);
			if (isDrill) items = [last.item, ...items];
			else await fetchQueue();
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}

	async function bury(): Promise<void> {
		const item = current;
		if (!item || busy) return;
		busy = true;
		try {
			// Until the end of the study day (falls back to 24 h when the queue gave none).
			const until = dayEndsAt ?? new Date(Date.now() + 24 * 3600 * 1000);
			await cardsApi.bulkCards({
				cardBulkRequest: { action: { type: 'bury', until }, cardIds: new Set([item.card.id]) }
			});
			pushToast(tr('review.buried'));
			await advance();
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}

	async function suspend(): Promise<void> {
		const item = current;
		if (!item || busy) return;
		busy = true;
		try {
			await cardsApi.bulkCards({
				cardBulkRequest: { action: { type: 'suspend' }, cardIds: new Set([item.card.id]) }
			});
			pushToast(tr('review.suspended'));
			await advance();
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}

	async function neverForget(): Promise<void> {
		const item = current;
		if (!item || busy) return;
		const seq = item.entry?.seq ?? item.card.note?.entrySeq ?? null;
		if (seq === null) {
			pushToast(tr('review.noEntry'));
			return;
		}
		busy = true;
		try {
			await wordsApi.setWordStatus({ seq, wordStatusSet: { status: 'never_forget' } });
			pushToast(tr('review.neverForgetSet'));
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}

	function noteSaved(n: Note): void {
		const item = current;
		if (item?.card.note) {
			item.card.note = { ...item.card.note, fields: n.fields, mnemonic: n.mnemonic, tags: n.tags };
		}
		editing = false;
		pushToast(tr('review.edit.saved'));
	}

	/* ---------- keyboard ---------- */

	function onKeydown(e: KeyboardEvent): void {
		if (e.ctrlKey || e.metaKey || e.altKey) return;
		const el = e.target as HTMLElement | null;
		const tag = el?.tagName;
		if (editing || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el?.isContentEditable) return;
		if (!current) return;
		const k = e.key;
		if (k === ' ' || k === 'Enter') {
			// A focused button handles its own activation.
			if (tag === 'BUTTON') return;
			e.preventDefault();
			if (!flipped) flipped = true;
			return;
		}
		if (/^[1-5]$/.test(k)) {
			const b = buttons[Number(k) - 1];
			if (b && flipped) {
				e.preventDefault();
				void grade(b);
			}
			return;
		}
		switch (k.toLowerCase()) {
			case 'u':
				void undo();
				break;
			case 'e':
				e.preventDefault();
				editing = true;
				break;
			case 'b':
				void bury();
				break;
			case 's':
				void suspend();
				break;
			case 'n':
				void neverForget();
				break;
			case 'f':
				furigana = !furigana;
				break;
			case 'p':
				cardView?.playFirst();
				break;
		}
	}

	const gradeKeys = $derived(fiveButtons ? '1-5' : '1-4');
	const reviewTypeKey = $derived<ReviewMessageKey | null>(
		current?.reviewType === 'drill' ? 'review.type.drill' : current?.reviewType === 'cram' ? 'review.type.cram' : null
	);
</script>

<svelte:window onkeydown={onKeydown} />

<h1>{tr('review.title')}</h1>

{#if !loaded}
	<p>{tr('review.loading')}</p>
{:else if drillUnavailable}
	<p role="status">{tr('review.drillUnavailable')}</p>
{:else if !current}
	<section class="done" aria-live="polite">
		<p><strong>{tr('review.done')}</strong></p>
		<p>{tr('review.doneHint')}</p>
		{#if !isDrill}
			<button class="kb-btn" type="button" onclick={reload} disabled={busy}>{tr('review.reload')}</button>
		{/if}
		{#if history.length > 0}
			<button class="kb-btn kb-btn-quiet" type="button" onclick={undo} disabled={busy}>{tr('review.undo')}</button>
		{/if}
	</section>
{:else}
	{#if counts}
		<p class="counts">{tr('review.counts', { new: counts._new, learning: counts.learning, review: counts.review })}</p>
	{/if}
	{#if reviewTypeKey}<p class="counts">{tr(reviewTypeKey)}</p>{/if}

	<div class="tools">
		<label class="inline"><input type="checkbox" bind:checked={furigana} /> {tr('review.furigana')}</label>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={() => (editing = true)}>{tr('review.edit')}</button>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={bury} disabled={busy}>{tr('review.bury')}</button>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={suspend} disabled={busy}>{tr('review.suspend')}</button>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={neverForget} disabled={busy}>
			{tr('review.neverForget')}
		</button>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={undo} disabled={busy || history.length === 0}>
			{tr('review.undo')}
		</button>
	</div>

	{#if editing && current.card.note}
		<NoteEditForm
			note={current.card.note}
			noteType={noteTypes.get(current.card.note.noteTypeId)}
			onsaved={noteSaved}
			oncancel={() => (editing = false)}
		/>
	{:else if rendered === null}
		<!-- unreachable: current is set -->
	{:else if rendered.ok}
		<CardView bind:this={cardView} rendered={rendered.card} {furigana} />
	{:else}
		<p class="error" role="alert">{tr('review.renderFailed', { detail: rendered.detail })}</p>
	{/if}

	{#if !editing}
		<div class="grading" role="group" aria-label={tr('review.gradeGroup')}>
			{#if !flipped}
				<button class="kb-btn flip" type="button" onclick={() => (flipped = true)}>{tr('review.flip')}</button>
			{:else}
				{#each buttons as b, i (b.label)}
					<button class="kb-btn grade" type="button" disabled={busy} onclick={() => grade(b)}>
						<span class="gl">{i + 1} · {tr(`review.grade.${b.label}`)}</span>
						<small>{previewText(b)}</small>
					</button>
				{/each}
			{/if}
		</div>
		<p class="help">{tr('review.keyHelp', { grades: gradeKeys })}</p>
	{/if}
{/if}

<style>
	.counts,
	.help {
		color: var(--kb-text-muted);
		text-align: center;
	}
	.tools {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		justify-content: center;
		align-items: center;
	}
	.grading {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		justify-content: center;
		margin: 1rem 0;
	}
	.grade {
		display: grid;
		gap: 0.1rem;
		min-width: 6rem;
		text-align: center;
	}
	.done {
		text-align: center;
	}
	.error {
		color: var(--kb-danger, #b91c1c);
	}
</style>
