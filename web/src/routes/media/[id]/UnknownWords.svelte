<script lang="ts">
	// The media's unknown words, most useful first, with one-tap status marks.
	import Button from '../../../lib/ui/Button.svelte';
	import DictionaryLink from '../../../lib/ui/DictionaryLink.svelte';
	import EmptyState from '../../../lib/ui/EmptyState.svelte';
	import LoadError from '../../../lib/ui/LoadError.svelte';
	import Panel from '../../../lib/ui/Panel.svelte';
	import SegmentedControl from '../../../lib/ui/SegmentedControl.svelte';
	import Skeleton from '../../../lib/ui/Skeleton.svelte';
	import { ut } from '../../../lib/ui/messages';
	import { tc } from '../../../lib/coverage/messages';
	import { UnknownWordsState } from '../../../lib/state/media.svelte';
	import { mx } from '../messages';

	interface Props {
		deckId: string;
		onchanged: () => void;
	}
	let { deckId, onchanged }: Props = $props();
	const vm = new UnknownWordsState(() => deckId);
</script>

<Panel title={mx('mx.unknownTitle')} flush>
	{#snippet action()}
		<SegmentedControl
			label={mx('mx.sortBy')}
			bind:value={vm.sort}
			options={[
				{ value: 'occurrence_count', label: tc('cov.sort.count') },
				{ value: 'freq_rank', label: tc('cov.sort.rank') }
			]}
		/>
	{/snippet}
	{#if !vm.loaded}
		<div class="pad"><Skeleton shape="row" count={5} /></div>
	{:else if vm.loadFailed && vm.items.length === 0}
		<LoadError onretry={() => vm.retry()} />
	{:else if vm.items.length === 0}
		<EmptyState art="success" title={tc('cov.noWords')} />
	{:else}
		<ul class="words">
			{#each vm.items as item (item.itemKey)}
				<li>
					<div class="w">
						{#if item.entry}
							<p class="line"><span class="hw" lang="ja">{item.entry.headword}</span>{#if item.entry.headwordReading !== item.entry.headword}<span class="rd" lang="ja">{item.entry.headwordReading}</span>{/if}</p>
							<p class="gl">{item.entry.glosses.slice(0, 3).join('; ')}</p>
						{:else}
							<p class="gl">{tc('cov.noEntry')}</p>
						{/if}
					</div>
					<span class="n kb-num" title={tc('cov.col.count')}>×{item.occurrenceCount}</span>
					{#if item.entrySeq != null}
						<div class="acts">
							<DictionaryLink seq={item.entrySeq} text={item.entry?.headword} />
							<Button size="sm" variant="tonal" icon="check" onclick={() => vm.mark(item, 'known', onchanged)}>{tc('cov.markKnown')}</Button>
							<Button size="sm" variant="quiet" onclick={() => vm.mark(item, 'ignored', onchanged)}>{tc('cov.markIgnore')}</Button>
						</div>
					{/if}
				</li>
			{/each}
		</ul>
		{#if vm.loadFailed}
			<div class="more-error"><LoadError size="banner" onretry={() => vm.retry()} /></div>
		{:else if vm.cursor}
			<div class="more"><Button variant="quiet" onclick={() => vm.load(true)}>{ut('app.loadMore')}</Button></div>
		{/if}
	{/if}
</Panel>

<style>
	.pad {
		padding: 0 var(--kb-space-5);
	}
	.words {
		margin: 0;
		padding: 0;
		list-style: none;
	}
	li {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2) var(--kb-space-3);
		padding: var(--kb-space-3) var(--kb-space-5);
		border-top: 1px solid var(--kb-color-border);
	}
	.w {
		flex: 1 1 14rem;
		min-width: 0;
	}
	.line {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 0 var(--kb-space-3);
	}
	.hw {
		font: var(--kb-type-title-sm);
		font-weight: 500;
	}
	.rd,
	.gl {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.gl {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.n {
		font: var(--kb-type-label);
		color: var(--kb-color-text-muted);
	}
	.acts {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1);
	}
	.more-error {
		padding: var(--kb-space-3);
	}
	.more {
		display: flex;
		justify-content: center;
		padding: var(--kb-space-2);
	}
</style>
