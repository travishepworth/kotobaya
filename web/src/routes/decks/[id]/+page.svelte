<script lang="ts">
	// One deck: Overview (items) and Options (the form), plus delete with a confirm dialog.
	import { page } from '$app/state';
	import Banner from '../../../lib/ui/Banner.svelte';
	import Button from '../../../lib/ui/Button.svelte';
	import Dialog from '../../../lib/ui/Dialog.svelte';
	import EmptyState from '../../../lib/ui/EmptyState.svelte';
	import Page from '../../../lib/ui/Page.svelte';
	import Skeleton from '../../../lib/ui/Skeleton.svelte';
	import Tabs from '../../../lib/ui/Tabs.svelte';
	import { ut, type UiMessageKey } from '../../../lib/ui/messages';
	import { td } from '../../../lib/decks/messages';
	import { DeckEditPage, type Deck } from '../../../lib/state/decks.svelte';
	import DeckForm from '../DeckForm.svelte';
	import DeckItems from './DeckItems.svelte';
	import { dk } from '../messages';

	const id = $derived(page.params.id ?? '');
	const s = new DeckEditPage();
	$effect(() => {
		if (id) s.load(id);
	});

	let tab = $state<'items' | 'options'>('items');
	let confirming = $state(false);
</script>

<svelte:head><title>{s.deck?.name ?? td('decks.title')} · Kotobaya</title></svelte:head>

{#if s.missing}
	<Page title={dk('dk.notFoundTitle')} back={{ href: '/decks', label: td('decks.title') }} width="measure">
		<EmptyState size="page" art="decks" title={dk('dk.notFoundTitle')} body={td('decks.notFound')}>
			{#snippet action()}<Button variant="primary" href="/decks">{td('decks.back')}</Button>{/snippet}
		</EmptyState>
	</Page>
{:else if s.deck && s.draft}
	{@const deck = s.deck}
	<Page
		title={deck.name}
		eyebrow={ut(`app.deckType.${deck.type}` as UiMessageKey)}
		lead={dk('dk.itemsCount', { n: deck.itemCount.toLocaleString() })}
		back={{ href: '/decks', label: td('decks.title') }}
		width="measure"
	>
		{#snippet actions()}
			<Button variant="primary" icon="review" href="/review">{ut('app.nav.review')}</Button>
		{/snippet}

		{#if deck.type === 'media'}<div class="note"><Banner>{td('decks.readOnly')}</Banner></div>{/if}

		<div class="tabs">
			<Tabs
				label={deck.name}
				bind:value={tab}
				tabs={[
					{ value: 'items', label: dk('dk.items'), count: deck.itemCount },
					{ value: 'options', label: dk('dk.options') }
				]}
			/>
		</div>

		{#if tab === 'items'}
			{@render items(deck)}
		{:else}
			<DeckForm
				bind:draft={s.draft}
				isNew={false}
				lists={[]}
				vocabulary={s.vocabulary}
				busy={s.busy}
				serverError={s.serverError}
				submitLabel={td('decks.save')}
				onsubmit={() => void s.save()}
			/>
			{#if deck.type !== 'media'}
				<section class="danger">
					<div>
						<h2>{dk('dk.danger')}</h2>
						<p>{dk('dk.dangerBody')}</p>
					</div>
					<Button variant="danger" icon="trash" onclick={() => (confirming = true)}>{td('decks.delete')}</Button>
				</section>
			{/if}
		{/if}
	</Page>

	<Dialog bind:open={confirming} title={dk('dk.deleteTitle', { name: deck.name })}>
		<p>{dk('dk.dangerBody')}</p>
		{#snippet actions()}
			<Button variant="quiet" onclick={() => (confirming = false)}>{ut('app.cancel')}</Button>
			<Button variant="danger" onclick={() => void s.remove()}>{td('decks.delete')}</Button>
		{/snippet}
	</Dialog>
{:else}
	<Page title={td('decks.title')} back={{ href: '/decks', label: td('decks.title') }} width="measure" hideTitle>
		<Skeleton shape="block" height="3rem" />
		<div class="gap"><Skeleton shape="row" count={6} /></div>
	</Page>
{/if}

{#snippet items(deck: Deck)}
	{#if deck.type === 'smart'}
		<Banner>{td('decks.itemsSmart')}</Banner>
	{:else}
		<DeckItems {deck} />
	{/if}
{/snippet}

<style>
	.note,
	.tabs {
		margin-bottom: var(--kb-space-5);
	}
	.gap {
		margin-top: var(--kb-space-5);
	}
	.danger {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: var(--kb-space-3);
		margin-top: var(--kb-space-8);
		padding: var(--kb-space-5);
		border: 1px solid var(--kb-color-danger-container);
		border-radius: var(--kb-radius-lg);
	}
	.danger h2 {
		font: var(--kb-type-title-sm);
	}
	.danger p {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
</style>
