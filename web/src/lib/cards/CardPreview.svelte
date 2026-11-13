<script lang="ts">
	// Preview pane: renders the card through the shared core (renderCard) into the shared CardView.
	import CardView from '../review/CardView.svelte';
	import { CardPreviewState, type Card, type NoteType } from '../state/cards.svelte';
	import { tc } from './messages';

	let {
		card,
		noteType,
		onheadword
	}: { card: Card; noteType: NoteType | undefined; onheadword?: (seq: number, headword: string) => void } = $props();

	const s = new CardPreviewState(
		() => card,
		() => noteType,
		() => onheadword
	);
</script>

<section aria-label={tc('cards.preview')}>
	{#if s.kinds.length > 1}
		<div class="tabs">
			{#each s.kinds as k (k)}
				<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={s.activeKind === k} onclick={() => (s.kind = k)}>
					{k}
				</button>
			{/each}
		</div>
	{/if}
	<div class="tabs">
		<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={s.side === 'front'} onclick={() => (s.side = 'front')}>
			{tc('cards.previewFront')}
		</button>
		<button type="button" class="kb-btn kb-btn-quiet" aria-pressed={s.side === 'back'} onclick={() => (s.side = 'back')}>
			{tc('cards.previewBack')}
		</button>
	</div>
	{#if s.ref.loading}
		<p class="muted">{tc('cards.previewLoading')}</p>
	{:else if s.ref.failed}
		<p class="err">{tc('cards.previewDictFailed')}</p>
	{/if}
	{#if s.rendered.ok}
		<CardView rendered={s.rendered.card} />
	{:else}
		<p class="err">{tc('cards.previewFailed', { detail: s.rendered.detail })}</p>
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
