<script lang="ts">
	// One search box for the dictionary, your notes and your cards (ADR-026). Plain text lists dictionary words
	// (flagged "In my cards") and then notes; card-search operators (deck:, is:, lapses>) list cards.
	import { onMount } from 'svelte';
	import Button from '../../lib/ui/Button.svelte';
	import Chip from '../../lib/ui/Chip.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import ListRow from '../../lib/ui/ListRow.svelte';
	import LoadError from '../../lib/ui/LoadError.svelte';
	import Page from '../../lib/ui/Page.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { ut } from '../../lib/ui/messages';
	import { tms } from '../../lib/search/messages';
	import { dictionaryHref } from '../../lib/state/dictionaryLink';
	import { MergedSearchPage, type NoteRow } from '../../lib/state/mergedSearch.svelte';
	import { noteHref } from '../../lib/state/noteLink';

	const s = new MergedSearchPage();
	onMount(() => {
		const q = new URLSearchParams(window.location.search).get('q');
		if (q) {
			s.query = q;
			s.searchNow();
		}
		return () => s.dispose();
	});

	function submit(e: Event): void {
		e.preventDefault();
		s.searchNow();
	}
	function support(n: NoteRow): string {
		return [n.reading, n.meaning].filter((x) => x !== '').join(' · ');
	}
</script>

<svelte:head><title>{tms('ms.title')} · Kotobaya</title></svelte:head>

<Page title={tms('ms.title')} width="measure">
	<form class="search" role="search" onsubmit={submit}>
		<TextField
			id="ms-q"
			type="search"
			label={tms('ms.label')}
			hideLabel
			icon="search"
			clearable
			placeholder={tms('ms.placeholder')}
			lang="ja"
			autocomplete="off"
			enterkeyhint="search"
			bind:value={s.query}
			oninput={() => s.queryChanged()}
		/>
		<Button variant="primary" type="submit">{tms('ms.submit')}</Button>
	</form>

	<div aria-busy={s.searching}>
		{#if s.failed}
			<LoadError onretry={() => s.searchNow()} />
		{:else if s.invalid}
			<EmptyState tone="error" art="alert" title={tms('ms.failed')} body={s.invalid} />
		{:else if s.searching && s.mode === 'idle'}
			<Skeleton shape="row" count={6} />
		{:else if s.mode === 'idle'}
			<EmptyState art="search" title={tms('ms.startTitle')} body={tms('ms.startBody')} />
		{:else if s.empty}
			<EmptyState art="search" title={tms('ms.noneTitle')} body={tms('ms.noneBody')} />
		{:else if s.mode === 'cards'}
			<h2 class="group">{tms('ms.cards')}</h2>
			<ul>
				{#each s.cardRows as n (n.noteId)}
					<li>
						<ListRow
							title={n.label}
							titleLang="ja"
							supporting={support(n)}
							meta={tms('ms.cardCount', { n: n.cardCount })}
							href={noteHref(n.noteId)}
						/>
					</li>
				{/each}
			</ul>
		{:else}
			{#if s.words.length > 0}
				<h2 class="group">{tms('ms.words')}</h2>
				<ul>
					{#each s.words as w (w.seq)}
						<li>
							<ListRow title={w.headword} titleLang="ja" supporting={[w.headword !== w.reading ? w.reading : '', w.meaning].filter((x) => x !== '').join(' · ')} href={dictionaryHref(w.seq)}>
								{#snippet trailing()}
									{#if w.inMyCards}<Chip kind="state" state="review" label={tms('ms.inCards')} />{/if}
								{/snippet}
							</ListRow>
						</li>
					{/each}
				</ul>
				{#if s.dictCursor !== null}
					<div class="more">
						<Button variant="quiet" loading={s.searching} onclick={() => void s.moreWords()}>{ut('app.loadMore')}</Button>
					</div>
				{/if}
			{/if}
			{#if s.noteRows.length > 0}
				<h2 class="group">{tms('ms.notes')}</h2>
				<ul>
					{#each s.noteRows as n (n.noteId)}
						<li>
							<ListRow
								title={n.label}
								titleLang="ja"
								supporting={support(n)}
								meta={tms('ms.cardCount', { n: n.cardCount })}
								href={noteHref(n.noteId)}
							>
								{#snippet trailing()}<Chip kind="state" state="review" label={tms('ms.inCards')} />{/snippet}
							</ListRow>
						</li>
					{/each}
				</ul>
			{/if}
		{/if}
	</div>
</Page>

<style>
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
	ul {
		display: flex;
		flex-direction: column;
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.group {
		margin: var(--kb-space-4) 0 var(--kb-space-2);
		font: var(--kb-type-title-sm);
		color: var(--kb-color-text-muted);
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--kb-space-3);
	}
</style>
