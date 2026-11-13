<script lang="ts">
	// Reader: the text on a reading measure, Japanese body type, unknown words underlined dotted (not only coloured);
	// tap a word for its entry. Furigana toggle, parts, parse overrides folded at the end.
	import { page } from '$app/state';
	import Banner from '../../../../lib/ui/Banner.svelte';
	import Button from '../../../../lib/ui/Button.svelte';
	import EmptyState from '../../../../lib/ui/EmptyState.svelte';
	import GrammarCapture from '../../../../lib/ui/GrammarCapture.svelte';
	import IconButton from '../../../../lib/ui/IconButton.svelte';
	import Select from '../../../../lib/ui/Select.svelte';
	import Skeleton from '../../../../lib/ui/Skeleton.svelte';
	import Switch from '../../../../lib/ui/Switch.svelte';
	import { tg } from '../../../../lib/grammar-capture/messages';
	import { tr } from '../../../../lib/reader/messages';
	import { locationLabel, OverrideListState, pieces, ReaderPage, scopeOf } from '../../../../lib/state/reader.svelte';
	import { mx } from '../../messages';
	import TokenSheet from './TokenSheet.svelte';

	const mediaId = $derived(page.params.id ?? '');
	const vm = new ReaderPage(() => mediaId);
	const ov = new OverrideListState(
		() => mediaId,
		() => vm.overridesVersion
	);
	let sentinel = $state<HTMLElement | null>(null);
	$effect(() => {
		const el = sentinel;
		if (!el || typeof IntersectionObserver === 'undefined') return;
		const io = new IntersectionObserver((entries) => {
			if (entries.some((e) => e.isIntersecting)) vm.nearEnd();
		});
		io.observe(el);
		return () => io.disconnect();
	});
	const segOptions = $derived([{ value: '', label: tr('rd.allSegments') }, ...vm.segments.map((s) => ({ value: s.id, label: s.title || String(s.ordinal + 1) }))]);
	const capturing = $derived(vm.sentences.find((s) => s.id === vm.capturing) ?? null);
</script>

<svelte:head><title>{tr('rd.title')} · Kotobaya</title></svelte:head>

<div class="reader-page">
	<div class="controls">
		{#if vm.segments.length > 0}
			<div class="seg"><Select label={tr('rd.segment')} hideLabel options={segOptions} bind:value={vm.segmentId} /></div>
		{/if}
		<div class="sw"><Switch label={mx('mx.ruby')} bind:checked={vm.furigana} /></div>
	</div>
	<p class="legend">
		<span class="tok known">{mx('mx.legend.known')}</span>
		<span class="tok learning">{mx('mx.legend.learning')}</span>
		<span class="tok new">{mx('mx.legend.new')}</span>
		<span class="hint">{mx('mx.tapHint')}</span>
	</p>

	{#if vm.showBanner}
		<div class="gap">
			<Banner>
				{tr('rd.banner')}
				{#snippet action()}
					<Button size="sm" variant="outline" onclick={() => vm.reparse()}>{tr('rd.reparse')}</Button>
					<Button size="sm" variant="quiet" onclick={() => vm.dismissBanner()}>{tr('rd.dismiss')}</Button>
				{/snippet}
			</Banner>
		</div>
	{/if}

	{#if !vm.loaded && vm.sentences.length === 0}
		<Skeleton shape="text" count={8} />
	{:else if vm.sentences.length === 0}
		<EmptyState art="media" title={mx('mx.emptyReader')} body={tr('rd.empty')} />
	{:else}
		<article class="text" lang="ja">
			{#each vm.sentences as s (s.id)}
				<div class="sentence">
					<p class="body">
						{#each pieces(s) as piece, i (i)}
							{#if piece.tokenIndex === null}{piece.text}{:else}
								{@const tok = s.tokens[piece.tokenIndex]}
								<span
									class="tok {vm.statusClass(tok.seq)}"
									class:sel={vm.selected?.sentenceId === s.id && vm.selected.index === piece.tokenIndex}
									role="button"
									tabindex="0"
									onclick={() => vm.select(s.id, piece.tokenIndex ?? 0)}
									onkeydown={(e) => {
										if (e.key === 'Enter' || e.key === ' ') {
											e.preventDefault();
											vm.select(s.id, piece.tokenIndex ?? 0);
										}
									}}
									>{#if vm.furigana && tok.reading && tok.reading !== piece.text}<ruby>{piece.text}<rt>{tok.reading}</rt></ruby>{:else}{piece.text}{/if}</span
								>
							{/if}
						{/each}
					</p>
					<div class="side" lang="en">
						{#if locationLabel(s)}<span class="loc">{locationLabel(s)}</span>{/if}
						<IconButton icon="flag" label={tg('gc.open')} onclick={() => vm.toggleCapture(s.id)} />
					</div>
				</div>
			{/each}
		</article>
	{/if}
	<div bind:this={sentinel} class="more">
		{#if vm.cursor}<Button variant="quiet" onclick={() => vm.load(true)}>{tr('rd.more')}</Button>{/if}
	</div>

	<details class="overrides">
		<summary>{tr('rd.overrides')} <span class="n">{ov.items.length}</span></summary>
		{#if ov.items.length === 0}
			<p class="hint">{tr('rd.overrides.none')}</p>
		{:else}
			<ul>
				{#each ov.items as o (o.id)}
					<li>
						<span lang="ja">{o.matchText} → {o.tokens.map((t) => t.surface).join(' | ')}</span>
						<span class="hint">{tr(`rd.overrides.scope.${scopeOf(o)}`)}</span>
						<Button size="sm" variant="quiet" onclick={() => ov.remove(o)}>{tr('rd.overrides.delete')}</Button>
					</li>
				{/each}
			</ul>
		{/if}
	</details>
</div>

{#if vm.selSentence && vm.selected}
	{#key `${vm.selected.sentenceId}:${vm.selected.index}`}
		<TokenSheet
			sentence={vm.selSentence}
			tokenIndex={vm.selected.index}
			onclose={() => vm.clearSelection()}
			onoverride={() => vm.overridden()}
			onstatus={(seq) => void vm.resolve(seq)}
		/>
	{/key}
{/if}
{#if capturing}<GrammarCapture sentence={capturing} onclose={() => vm.closeCapture()} />{/if}

<style>
	.reader-page {
		max-width: calc(var(--kb-size-measure) + 2 * var(--kb-space-6));
		margin: 0 auto;
		padding: var(--kb-space-5) var(--kb-space-4) var(--kb-space-16);
	}
	.controls {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: var(--kb-space-3);
	}
	.seg {
		flex: 1 1 12rem;
		max-width: 20rem;
	}
	.sw {
		flex: 0 1 12rem;
	}
	.legend {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1) var(--kb-space-4);
		margin: var(--kb-space-3) 0 var(--kb-space-6);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.gap {
		margin-bottom: var(--kb-space-5);
	}
	.text {
		display: flex;
		flex-direction: column;
	}
	.sentence {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		gap: var(--kb-space-2);
		padding: var(--kb-space-2) 0;
	}
	.body {
		font: var(--kb-type-jp-body);
		font-size: clamp(1.1875rem, 1.1rem + 0.4vw, 1.375rem);
		line-height: var(--kb-ruby-lh);
		word-break: auto-phrase;
	}
	.side {
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		opacity: 0.55;
		transition: opacity var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.sentence:hover .side,
	.side:focus-within {
		opacity: 1;
	}
	.loc {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		white-space: nowrap;
	}
	.tok {
		cursor: pointer;
		border-radius: 2px;
		text-decoration-thickness: 2px;
		text-underline-offset: 0.3em;
	}
	.tok.new {
		text-decoration-line: underline;
		text-decoration-style: dotted;
		text-decoration-color: var(--kb-color-state-new);
	}
	.tok.learning {
		text-decoration-line: underline;
		text-decoration-style: solid;
		text-decoration-color: var(--kb-color-state-learning);
	}
	.tok.known,
	.tok.never_forget {
		text-decoration-color: var(--kb-color-state-review);
	}
	.legend .tok.known {
		text-decoration-line: underline;
	}
	.tok.ignored,
	.tok.blacklisted,
	.tok.unmapped {
		color: var(--kb-color-text-muted);
	}
	.tok:hover {
		background: var(--kb-color-surface-hover);
	}
	.tok:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 1px;
	}
	.tok.sel {
		background: var(--kb-color-highlight);
		color: var(--kb-color-on-highlight);
	}
	.more {
		display: flex;
		justify-content: center;
		min-height: 1px;
		margin: var(--kb-space-4) 0;
	}
	.overrides {
		margin-top: var(--kb-space-8);
		padding-top: var(--kb-space-3);
		border-top: 1px solid var(--kb-color-border);
	}
	.overrides summary {
		display: flex;
		align-items: center;
		gap: var(--kb-space-2);
		min-height: var(--kb-size-touch-min);
		font: var(--kb-type-label);
		cursor: pointer;
	}
	.n {
		padding: 0 var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-caption);
	}
	.overrides ul {
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.overrides li {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2);
		padding: var(--kb-space-1) 0;
	}
	.hint {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
</style>
