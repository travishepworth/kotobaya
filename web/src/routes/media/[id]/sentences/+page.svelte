<script lang="ts">
	// Sentences of a media as rows (the one unknown word of an i+1 sentence highlighted), with "Make card" opening a
	// short form in a dialog.
	import { page } from '$app/state';
	import Button from '../../../../lib/ui/Button.svelte';
	import Dialog from '../../../../lib/ui/Dialog.svelte';
	import EmptyState from '../../../../lib/ui/EmptyState.svelte';
	import GrammarCapture from '../../../../lib/ui/GrammarCapture.svelte';
	import IconButton from '../../../../lib/ui/IconButton.svelte';
	import SegmentedControl from '../../../../lib/ui/SegmentedControl.svelte';
	import Select from '../../../../lib/ui/Select.svelte';
	import Skeleton from '../../../../lib/ui/Skeleton.svelte';
	import Switch from '../../../../lib/ui/Switch.svelte';
	import TextField from '../../../../lib/ui/TextField.svelte';
	import { tg } from '../../../../lib/grammar-capture/messages';
	import { ts } from '../../../../lib/sentences/messages';
	import { formatMs, pieces, playBlob, SentencesPage, type SentenceFilter } from '../../../../lib/state/reader.svelte';

	const mediaId = $derived(page.params.id ?? '');
	const vm = new SentencesPage(() => mediaId);
	const segOptions = $derived([{ value: '', label: ts('sn.allSegments') }, ...vm.segments.map((s) => ({ value: s.id, label: s.title || String(s.ordinal + 1) }))]);
	const deckOptions = $derived([{ value: '', label: ts('sn.form.defaultDeck') }, ...vm.decks.map((d) => ({ value: d.id, label: d.name }))]);
	const capturing = $derived(vm.rows.find((s) => s.id === vm.capturing) ?? null);
	let formOpen = $state(false);
	$effect(() => {
		formOpen = vm.editing !== null;
	});
</script>

<svelte:head><title>{ts('sn.title')} · Kotobaya</title></svelte:head>

<div class="sentences">
	<div class="controls">
		<SegmentedControl
			label={ts('sn.filter')}
			bind:value={vm.filter}
			options={[
				{ value: 'all' as SentenceFilter, label: ts('sn.filter.all') },
				{ value: 'i+1' as SentenceFilter, label: ts('sn.filter.i1') },
				{ value: 'i+0' as SentenceFilter, label: ts('sn.filter.i0') }
			]}
		/>
		{#if vm.segments.length > 0}
			<div class="seg"><Select label={ts('sn.segment')} hideLabel options={segOptions} bind:value={vm.segmentId} /></div>
		{/if}
		<div class="q"><TextField label={ts('sn.search')} hideLabel type="search" icon="search" lang="ja" placeholder={ts('sn.search')} bind:value={vm.q} /></div>
	</div>

	{#if !vm.loaded && vm.rows.length === 0}
		<Skeleton shape="row" count={6} />
	{:else if vm.rows.length === 0}
		<EmptyState art="search" title={ts('sn.empty')} />
	{:else}
		<ul class="rows">
			{#each vm.rows as s (s.id)}
				<li>
					<p class="text" lang="ja">
						{#each pieces(s) as piece, i (i)}
							{#if piece.tokenIndex !== null && s.tokens[piece.tokenIndex].seq != null && s.tokens[piece.tokenIndex].seq === vm.unknown[s.id]}<mark>{piece.text}</mark>{:else}{piece.text}{/if}
						{/each}
					</p>
					<div class="acts">
						{#if s.startMs != null}<span class="at kb-num">{ts('sn.at', { time: formatMs(s.startMs) })}</span>{/if}
						{#if s.audioBlobHash}
							{@const hash = s.audioBlobHash}
							<IconButton icon="audio" label={ts('sn.play')} onclick={() => playBlob(hash)} />
						{/if}
						<IconButton icon="flag" label={tg('gc.open')} onclick={() => vm.toggleCapture(s.id)} />
						<Button size="sm" variant="tonal" icon="plus" onclick={() => vm.open(s)}>{ts('sn.make')}</Button>
					</div>
				</li>
			{/each}
		</ul>
		{#if vm.cursor}<div class="more"><Button variant="quiet" onclick={() => vm.load(true)}>{ts('sn.more')}</Button></div>{/if}
	{/if}
</div>

{#if vm.editing}
	{@const ed = vm.editing}
	<Dialog bind:open={formOpen} title={ts('sn.make')} size="lg" onclose={() => vm.cancel()}>
		<form
			id="sn-form"
			class="form"
			onsubmit={(e) => {
				e.preventDefault();
				void vm.create();
			}}
		>
			<TextField label={ts('sn.form.sentence')} lang="ja" bind:value={vm.formSentence} />
			<TextField label={ts('sn.form.reading')} lang="ja" bind:value={vm.formReading} />
			<TextField label={ts('sn.form.translation')} bind:value={vm.formTranslation} />
			<Select label={ts('sn.form.deck')} options={deckOptions} bind:value={vm.formDeck} />
			{#if vm.unknown[ed.id] != null}<Switch label={ts('sn.form.alsoVocab')} bind:checked={vm.alsoVocab} />{/if}
		</form>
		{#snippet actions()}
			<Button variant="quiet" onclick={() => vm.cancel()}>{ts('sn.form.cancel')}</Button>
			<Button variant="primary" type="submit" form="sn-form">{ts('sn.form.create')}</Button>
		{/snippet}
	</Dialog>
{/if}
{#if capturing}<GrammarCapture sentence={capturing} onclose={() => vm.closeCapture()} />{/if}

<style>
	.sentences {
		max-width: calc(var(--kb-size-measure) + 2 * var(--kb-space-10));
		margin: 0 auto;
		padding: var(--kb-space-5) var(--kb-space-4) var(--kb-space-16);
	}
	.controls {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2);
		margin-bottom: var(--kb-space-5);
	}
	.seg {
		flex: 0 1 12rem;
		min-width: 0;
	}
	.q {
		flex: 1 1 12rem;
		min-width: 0;
	}
	.rows {
		margin: 0;
		padding: 0;
		list-style: none;
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
	}
	li {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		padding: var(--kb-space-4) var(--kb-space-5) var(--kb-space-2);
		border-bottom: 1px solid var(--kb-color-border);
	}
	li:last-child {
		border-bottom: 0;
	}
	.text {
		font: var(--kb-type-jp-body);
		font-size: 1.1875rem;
	}
	mark {
		padding: 0 0.1em;
		border-radius: 2px;
		background: var(--kb-color-highlight);
		color: var(--kb-color-on-highlight);
	}
	.acts {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: flex-end;
		gap: var(--kb-space-1);
	}
	.at {
		margin-inline-end: auto;
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--kb-space-3);
	}
	.form {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		color: var(--kb-color-text);
	}
</style>
