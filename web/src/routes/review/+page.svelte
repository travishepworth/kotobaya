<script lang="ts">
	import { page } from '$app/state';
	import CardView from '../../lib/review/CardView.svelte';
	import NoteEditForm from '../../lib/review/NoteEditForm.svelte';
	import { tr } from '../../lib/review/messages';
	import { ReviewPage } from '../../lib/state/review.svelte';

	const s = new ReviewPage();
	let cardView: CardView | undefined = $state();

	const drillSource = $derived(page.url.searchParams.get('drill'));

	// (Re)load whenever the drill source changes; start() reads no state, so this runs once per source.
	$effect(() => {
		s.start(drillSource);
	});

	/* ---------- keyboard ---------- */

	function onKeydown(e: KeyboardEvent): void {
		if (e.ctrlKey || e.metaKey || e.altKey) return;
		const el = e.target as HTMLElement | null;
		const tag = el?.tagName;
		if (s.editing || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el?.isContentEditable) return;
		if (!s.current) return;
		const k = e.key;
		if (k === ' ' || k === 'Enter') {
			// A focused button handles its own activation.
			if (tag === 'BUTTON') return;
			e.preventDefault();
			if (!s.flipped) s.flip();
			return;
		}
		if (/^[1-5]$/.test(k)) {
			const b = s.buttons[Number(k) - 1];
			if (b && s.flipped) {
				e.preventDefault();
				void s.grade(b);
			}
			return;
		}
		switch (k.toLowerCase()) {
			case 'u':
				void s.undo();
				break;
			case 'e':
				e.preventDefault();
				s.startEdit();
				break;
			case 'b':
				void s.bury();
				break;
			case 's':
				void s.suspend();
				break;
			case 'n':
				void s.neverForget();
				break;
			case 'f':
				s.toggleFurigana();
				break;
			case 'p':
				cardView?.playFirst();
				break;
		}
	}
</script>

<svelte:window onkeydown={onKeydown} />

<h1>{tr('review.title')}</h1>

{#if !s.loaded}
	<p>{tr('review.loading')}</p>
{:else if s.drillUnavailable}
	<p role="status">{tr('review.drillUnavailable')}</p>
{:else if !s.current}
	<section class="done" aria-live="polite">
		<p><strong>{tr('review.done')}</strong></p>
		<p>{tr('review.doneHint')}</p>
		{#if !s.isDrill}
			<button class="kb-btn" type="button" onclick={() => s.reload()} disabled={s.busy}>{tr('review.reload')}</button>
		{/if}
		{#if s.history.length > 0}
			<button class="kb-btn kb-btn-quiet" type="button" onclick={() => s.undo()} disabled={s.busy}>{tr('review.undo')}</button>
		{/if}
	</section>
{:else}
	{#if s.counts}
		<p class="counts">{tr('review.counts', { new: s.counts._new, learning: s.counts.learning, review: s.counts.review })}</p>
	{/if}
	{#if s.reviewTypeKey}<p class="counts">{tr(s.reviewTypeKey)}</p>{/if}

	<div class="tools">
		<label class="inline"><input type="checkbox" bind:checked={s.furigana} /> {tr('review.furigana')}</label>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={() => s.startEdit()}>{tr('review.edit')}</button>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={() => s.bury()} disabled={s.busy}>{tr('review.bury')}</button>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={() => s.suspend()} disabled={s.busy}>{tr('review.suspend')}</button>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={() => s.neverForget()} disabled={s.busy}>
			{tr('review.neverForget')}
		</button>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={() => s.undo()} disabled={s.busy || s.history.length === 0}>
			{tr('review.undo')}
		</button>
	</div>

	{#if s.editing && s.current.card.note}
		<NoteEditForm
			note={s.current.card.note}
			noteType={s.noteTypeOf(s.current.card.note)}
			onsaved={(n) => s.noteSaved(n)}
			oncancel={() => s.cancelEdit()}
		/>
	{:else if s.rendered === null}
		<!-- unreachable: current is set -->
	{:else if s.rendered.ok}
		{#if s.noExample}
			<p class="notice">{tr('review.noExampleSentence')}</p>
		{/if}
		<CardView bind:this={cardView} rendered={s.rendered.card} furigana={s.furigana} />
	{:else}
		<p class="error" role="alert">{tr('review.renderFailed', { detail: s.rendered.detail })}</p>
	{/if}

	{#if !s.editing}
		<div class="grading" role="group" aria-label={tr('review.gradeGroup')}>
			{#if !s.flipped}
				<button class="kb-btn flip" type="button" onclick={() => s.flip()}>{tr('review.flip')}</button>
			{:else}
				{#each s.buttons as b, i (b.label)}
					<button class="kb-btn grade" type="button" disabled={s.busy} onclick={() => s.grade(b)}>
						<span class="gl">{i + 1} · {tr(`review.grade.${b.label}`)}</span>
						<small>{s.previewText(b)}</small>
					</button>
				{/each}
			{/if}
		</div>
		<p class="help">{tr('review.keyHelp', { grades: s.gradeKeys })}</p>
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
	.notice {
		color: var(--kb-muted, #6b7280);
		font-style: italic;
	}
	.error {
		color: var(--kb-danger, #b91c1c);
	}
</style>
