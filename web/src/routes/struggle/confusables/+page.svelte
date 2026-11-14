<script lang="ts">
	import Button from '../../../lib/ui/Button.svelte';
	import EmptyState from '../../../lib/ui/EmptyState.svelte';
	import LoadError from '../../../lib/ui/LoadError.svelte';
	import Page from '../../../lib/ui/Page.svelte';
	import SegmentedControl from '../../../lib/ui/SegmentedControl.svelte';
	import Skeleton from '../../../lib/ui/Skeleton.svelte';
	import { ut } from '../../../lib/ui/messages';
	import { CONFUSABLE_STATUSES, ConfusablesPage } from '../../../lib/state/struggle.svelte';
	import { noteTitle } from '../../../lib/ui/cardState';
	import { dictionaryLinkFor, type DictionaryRef } from '../../../lib/state/dictionaryLink';
	import { tg, tgEnum } from '../messages';

	const vm = new ConfusablesPage();
</script>

{#snippet word(n: Parameters<typeof noteTitle>[0] & DictionaryRef)}
	{@const href = dictionaryLinkFor(n)}
	{#if href}
		<a class="w" {href} title={ut('ui.openInDictionaryFor', { text: noteTitle(n) })}>{noteTitle(n)}</a>
	{:else}
		<span class="w">{noteTitle(n)}</span>
	{/if}
{/snippet}

<svelte:head><title>{tg('st.confusables')} · Kotobaya</title></svelte:head>

<Page title={tg('st.confusables')} lead={tg('st.confusablesLead')} back={{ href: '/struggle', label: tg('st.title') }} width="measure">
	{#snippet actions()}<Button variant="primary" icon="review" href="/review?drill=confusables">{tg('st.drillConfusables')}</Button>{/snippet}

	<div class="filter">
		<SegmentedControl
			label={tg('st.filter')}
			bind:value={vm.status}
			options={CONFUSABLE_STATUSES.map((s) => ({ value: s, label: tgEnum('st.status', s) }))}
		/>
	</div>

	{#if !vm.loaded}
		<Skeleton shape="row" count={5} />
	{:else if vm.loadFailed && vm.pairs.length === 0}
		<LoadError onretry={() => vm.retry()} />
	{:else if vm.pairs.length === 0}
		<EmptyState art="success" title={tg('st.confusablesNone')} />
	{:else}
		<ul class="pairs">
			{#each vm.pairs as p (`${p.noteAId}/${p.noteBId}/${p.reason}`)}
				<li>
					<div class="pair" lang="ja">
						{@render word(p.noteA)}
						<span class="vs" lang="en">{tg('st.vs')}</span>
						{@render word(p.noteB)}
					</div>
					<p class="reason">{tgEnum('st.reason', p.reason)}</p>
					<div class="acts">
						{#if p.status !== 'rejected'}
							<Button size="sm" variant="quiet" disabled={vm.busy} onclick={() => vm.decide(p, 'rejected')}>{tg('st.reject')}</Button>
						{/if}
						{#if p.status !== 'confirmed'}
							<Button size="sm" variant="tonal" icon="check" disabled={vm.busy} onclick={() => vm.decide(p, 'confirmed')}>{tg('st.confirm')}</Button>
						{/if}
					</div>
				</li>
			{/each}
		</ul>
		{#if vm.loadFailed}
			<div class="more-error"><LoadError size="banner" onretry={() => vm.retry()} /></div>
		{:else if vm.cursor}
			<div class="more"><Button variant="quiet" loading={vm.busy} onclick={() => vm.load(true)}>{ut('app.loadMore')}</Button></div>
		{/if}
	{/if}
</Page>

<style>
	.filter {
		margin-bottom: var(--kb-space-4);
	}
	.pairs {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 17rem), 1fr));
		gap: var(--kb-space-3);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	li {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		padding: var(--kb-space-4);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
	}
	.pair {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		justify-content: center;
		gap: var(--kb-space-3);
		padding: var(--kb-space-3) 0;
	}
	.w {
		max-width: 100%;
		font: var(--kb-type-title);
		font-size: 1.5rem;
		text-align: center;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	a.w {
		color: var(--kb-color-text);
		text-decoration: underline dotted var(--kb-color-border-strong);
		text-underline-offset: 0.2em;
	}
	a.w:hover {
		color: var(--kb-color-link);
	}
	.vs {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.reason {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		text-align: center;
	}
	.acts {
		display: flex;
		justify-content: flex-end;
		gap: var(--kb-space-2);
	}
	.more-error {
		margin-top: var(--kb-space-3);
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--kb-space-3);
	}
</style>
