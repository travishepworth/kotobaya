<script lang="ts">
	import Button from '../../../lib/ui/Button.svelte';
	import Chip from '../../../lib/ui/Chip.svelte';
	import EmptyState from '../../../lib/ui/EmptyState.svelte';
	import ListRow from '../../../lib/ui/ListRow.svelte';
	import Page from '../../../lib/ui/Page.svelte';
	import Skeleton from '../../../lib/ui/Skeleton.svelte';
	import { ut } from '../../../lib/ui/messages';
	import { LeechesPage } from '../../../lib/state/struggle.svelte';
	import { noteTitle } from '../../../lib/ui/cardState';
	import { tg } from '../messages';

	const vm = new LeechesPage();
	$effect(() => {
		void vm.load(false);
	});
</script>

<svelte:head><title>{tg('st.leeches')} · Kotobaya</title></svelte:head>

<Page title={tg('st.leeches')} lead={tg('st.leechesLead')} back={{ href: '/struggle', label: tg('st.title') }} width="measure">
	{#snippet actions()}
		{#if vm.cards.length > 0}<Button variant="primary" icon="review" href="/review?drill=leeches">{tg('st.drillLeeches')}</Button>{/if}
	{/snippet}
	{#if !vm.loaded}
		<Skeleton shape="row" count={6} />
	{:else if vm.cards.length === 0}
		<EmptyState size="page" art="success" title={tg('st.leechesNone')} body={tg('st.calmBody')} />
	{:else}
		<div class="rows">
			{#each vm.cards as c (c.id)}
				<ListRow title={c.note ? noteTitle(c.note) : c.noteId} titleLang="ja" supporting={c.kind} href="/notes?note={c.noteId}">
					{#snippet trailing()}
						<span class="lapses kb-num">{tg('st.lapses', { n: c.lapses })}</span>
						{#if c.suspended}<Chip kind="state" state="suspended" label={tg('st.suspended')} />{/if}
					{/snippet}
				</ListRow>
			{/each}
		</div>
		{#if vm.cursor}
			<div class="more"><Button variant="quiet" loading={vm.busy} onclick={() => vm.load(true)}>{ut('app.loadMore')}</Button></div>
		{/if}
	{/if}
	<p class="note">{tg('st.drillNote')}</p>
</Page>

<style>
	.rows {
		display: flex;
		flex-direction: column;
		padding: var(--kb-space-1);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
	}
	.lapses {
		padding: 1px var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-danger-container);
		color: var(--kb-color-on-danger-container);
		font: var(--kb-type-caption);
		font-weight: 600;
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--kb-space-3);
	}
	.note {
		margin-top: var(--kb-space-5);
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
</style>
