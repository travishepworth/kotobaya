<script lang="ts">
	// Preview pane: renders the card through the shared core (renderCard) into the shared CardView.
	import type { Card, NoteType } from '../api/generated';
	import { renderCard, type CardSide, type RenderedCard } from '../core/index';
	import CardView from '../review/CardView.svelte';
	import { MEDIA_URL_TEMPLATE, toCoreNoteType } from '../review/model';
	import { tc } from './messages';

	let { card, noteType }: { card: Card; noteType: NoteType | undefined } = $props();

	let side = $state<CardSide>('front');

	// A newly chosen card starts on its front.
	$effect(() => {
		void card.id;
		side = 'front';
	});

	type Rendered = { ok: true; card: RenderedCard } | { ok: false; detail: string };

	const rendered = $derived.by((): Rendered => {
		const note = card.note;
		if (!note || !noteType) return { ok: false, detail: tc('cards.previewMissingType') };
		try {
			return {
				ok: true,
				card: renderCard({
					noteType: toCoreNoteType(noteType),
					kind: card.kind,
					fields: note.fields,
					mnemonic: note.mnemonic,
					dictionary: null,
					kanji: null,
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
	<div class="tabs">
		<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={side === 'front'} onclick={() => (side = 'front')}>
			{tc('cards.previewFront')}
		</button>
		<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={side === 'back'} onclick={() => (side = 'back')}>
			{tc('cards.previewBack')}
		</button>
	</div>
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
	.err {
		color: var(--kb-error);
	}
</style>
