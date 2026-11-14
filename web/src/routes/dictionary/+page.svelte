<script lang="ts">
	// Dictionary: search on the left, the entry set for reading on the right (phones: the entry is a pushed view).
	import { onMount } from 'svelte';
	import Chip from '../../lib/ui/Chip.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import Icon from '../../lib/ui/Icon.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { ut } from '../../lib/ui/messages';
	import { td } from '../../lib/dictionary/messages';
	import { DictionaryPage } from '../../lib/state/dictionary.svelte';
	import { seqFromSearch } from '../../lib/state/dictionaryLink';
	import EntryView from './EntryView.svelte';
	import { dx } from './messages';

	const vm = new DictionaryPage();
	onMount(() => {
		const seq = seqFromSearch(window.location.search);
		if (seq !== null) open(seq);
		// Desktop: straight to typing.
		else if (window.matchMedia('(min-width: 1024px) and (pointer: fine)').matches) searchBox()?.focus();
		return () => vm.dispose();
	});

	let pane = $state<'results' | 'entry'>('results');
	let lastQuery = $state('');

	function searchBox(): HTMLInputElement | null {
		return document.querySelector<HTMLInputElement>('#dict-q');
	}

	function submit(e: Event): void {
		e.preventDefault();
		vm.searchNow();
		lastQuery = vm.query.trim();
	}

	function oninput(): void {
		vm.queryChanged();
		lastQuery = vm.query.trim();
		pane = 'results';
	}

	function open(seq: number): void {
		pane = 'entry';
		void vm.open(seq);
		window.scrollTo({ top: 0 });
	}

	function tryQuery(q: string): void {
		vm.query = q;
		lastQuery = q;
		vm.searchNow();
	}

	function onkeydown(e: KeyboardEvent): void {
		const t = e.target as HTMLElement | null;
		if (e.key === '/' && !(t?.tagName === 'INPUT' || t?.tagName === 'TEXTAREA' || t?.isContentEditable)) {
			e.preventDefault();
			searchBox()?.focus();
		}
	}

	// Typing an empty query clears the list: also reflected in the "last query" shown in the no-results state.
	$effect(() => {
		if (vm.query.trim() === '') lastQuery = '';
	});
</script>

<svelte:window {onkeydown} />
<svelte:head><title>{td('dict.title')} · Kotobaya</title></svelte:head>

<div class="dict-frame">
<div class="dict" class:show-entry={pane === 'entry'}>
	<section class="list" aria-label={dx('dx.results')}>
		<h1 class="title">{td('dict.title')}</h1>
		<form class="search" role="search" onsubmit={submit}>
			<TextField
				id="dict-q"
				type="search"
				label={dx('dx.searchLabel')}
				hideLabel
				icon="search"
				clearable
				placeholder={dx('dx.placeholder')}
				lang="ja"
				autocomplete="off"
				enterkeyhint="search"
				bind:value={vm.query}
				{oninput}
			/>
			<Chip
				kind="filter"
				label={dx('dx.commonOnly')}
				selected={vm.commonOnly}
				onclick={() => {
					vm.commonOnly = !vm.commonOnly;
					void vm.runSearch(true);
				}}
			/>
		</form>

		<div class="results" aria-busy={vm.searching}>
			{#if vm.searching && vm.items.length === 0}
				<Skeleton shape="row" count={6} />
			{:else if !vm.searched}
				<div class="start">
					<EmptyState art="dictionary" title={dx('dx.startTitle')} body={dx('dx.startBody')}>
						{#snippet action()}
							<div class="tries">
								<span>{dx('dx.try')}</span>
								<Chip kind="tag" lang="ja" label="取る" onclick={() => tryQuery('取る')} />
								<Chip kind="tag" label="toru" onclick={() => tryQuery('toru')} />
								<Chip kind="tag" label="take" onclick={() => tryQuery('take')} />
							</div>
						{/snippet}
					</EmptyState>
				</div>
			{:else if vm.items.length === 0}
				<EmptyState art="search" title={dx('dx.noneTitle', { q: lastQuery })} body={dx('dx.noneBody')} />
			{:else}
				<ul>
					{#each vm.items as it (it.seq)}
						<li>
							<button
								type="button"
								class="result"
								aria-current={it.seq === vm.selectedSeq ? 'true' : undefined}
								onclick={() => open(it.seq)}
							>
								<span class="line">
									<span class="hw" lang="ja">{it.headword}</span>
									{#if it.headword !== it.headwordReading}<span class="rd" lang="ja">{it.headwordReading}</span>{/if}
								</span>
								<span class="gloss">{it.glosses.join('; ')}</span>
								{#if it.noteId !== null || it.isCommon || it.retired}
									<span class="marks">
										{#if it.noteId !== null}<span class="mark in"><Icon name="check" size={12} />{dx('dx.inNotes')}</span>{/if}
										{#if it.isCommon}<span class="mark">{dx('dx.common')}</span>{/if}
										{#if it.retired}<span class="mark">{dx('dx.retired')}</span>{/if}
									</span>
								{/if}
							</button>
						</li>
					{/each}
				</ul>
				{#if vm.cursor !== null}
					<div class="more">
						<Button variant="quiet" loading={vm.searching} onclick={() => void vm.runSearch(false)}>{ut('app.loadMore')}</Button>
					</div>
				{/if}
			{/if}
		</div>
	</section>

	<section class="detail" aria-label={td('dict.entry')}>
		<div class="back">
			<Button variant="quiet" icon="chevronLeft" onclick={() => (pane = 'results')}>{dx('dx.backToResults')}</Button>
		</div>
		{#if vm.loadingEntry}
			<div class="loading" aria-busy="true">
				<span class="kb-sr">{dx('dx.loadingEntry')}</span>
				<Skeleton shape="block" height="3.5rem" />
				<Skeleton shape="text" count={5} />
			</div>
		{:else if vm.entry}
			<EntryView {vm} entry={vm.entry} />
		{:else}
			<div class="pick"><EmptyState art="dictionary" title={dx('dx.pickTitle')} body={dx('dx.pickBody')} /></div>
		{/if}
	</section>
</div>
</div>

<style>
	.dict-frame {
		container-type: inline-size;
	}
	.dict {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		min-height: 100dvh;
	}
	.list {
		display: flex;
		flex-direction: column;
		min-width: 0;
		padding: var(--kb-space-5) var(--kb-space-4) var(--kb-space-8);
	}
	.title {
		font: var(--kb-type-headline);
		margin-bottom: var(--kb-space-4);
	}
	.search {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		align-items: center;
		margin-bottom: var(--kb-space-4);
	}
	.search > :global(.field) {
		flex: 1 1 14rem;
	}
	.results ul {
		display: flex;
		flex-direction: column;
		margin: 0 calc(-1 * var(--kb-space-2));
		padding: 0;
		list-style: none;
	}
	.result {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 2px;
		width: 100%;
		min-height: 3.5rem;
		padding: var(--kb-space-3) var(--kb-space-3) var(--kb-space-3) var(--kb-space-4);
		border: 0;
		border-radius: var(--kb-radius-md);
		background: transparent;
		color: var(--kb-color-text);
		text-align: start;
		cursor: pointer;
		transition: background-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.result:hover {
		background: var(--kb-color-surface-hover);
	}
	.result[aria-current='true'] {
		background: var(--kb-color-surface-selected);
	}
	.result[aria-current='true']::before {
		content: '';
		position: absolute;
		inset-block: var(--kb-space-3);
		left: 0;
		width: 3px;
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-primary);
	}
	.result:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: -2px;
	}
	.line {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 0 var(--kb-space-3);
	}
	.hw {
		font: var(--kb-type-title-sm);
		font-size: 1.1875rem;
		font-weight: 500;
	}
	.rd {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.gloss {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.marks {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1);
		margin-top: var(--kb-space-1);
	}
	.mark {
		display: inline-flex;
		align-items: center;
		gap: 3px;
		padding: 0 var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.mark.in {
		background: var(--kb-color-state-review-container);
		color: var(--kb-color-state-review);
	}
	.more {
		display: flex;
		justify-content: center;
		padding-top: var(--kb-space-3);
	}
	.start {
		padding-top: var(--kb-space-4);
	}
	.tries {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: center;
		gap: var(--kb-space-2);
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}

	.detail {
		display: none;
		min-width: 0;
		padding: var(--kb-space-3) var(--kb-space-4) var(--kb-space-12);
	}
	.loading {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		max-width: var(--kb-size-measure);
	}
	.pick {
		display: grid;
		place-items: center;
		min-height: 60dvh;
	}
	/* Phones and small tablets: the entry is a pushed view with a way back to the results. */
	.show-entry .list {
		display: none;
	}
	.show-entry .detail {
		display: block;
	}
	.back {
		margin: 0 0 var(--kb-space-3) calc(-1 * var(--kb-space-2));
	}

	/* Side by side from 46 rem of room: list 40 % / entry 60 %; from 60 rem a fixed 23.75 rem list. */
	@container (min-width: 46rem) {
		.dict {
			grid-template-columns: minmax(18rem, 2fr) minmax(0, 3fr);
		}
		.list,
		.show-entry .list {
			display: flex;
			position: sticky;
			top: 0;
			align-self: start;
			max-height: 100dvh;
			overflow-y: auto;
			border-inline-end: 1px solid var(--kb-color-border);
			padding: var(--kb-space-8) var(--kb-space-5) var(--kb-space-8);
		}
		.detail {
			display: block;
			padding: var(--kb-space-10) var(--kb-space-8) var(--kb-space-16);
		}
		.back {
			display: none;
		}
	}
	@container (min-width: 60rem) {
		.dict {
			grid-template-columns: 23.75rem minmax(0, 1fr);
		}
		.detail {
			padding-inline: var(--kb-space-12);
		}
	}
</style>
