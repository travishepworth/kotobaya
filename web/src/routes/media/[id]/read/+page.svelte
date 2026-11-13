<script lang="ts">
	import { page } from '$app/state';
	import GrammarCapture from '../../../../lib/grammar-capture/GrammarCapture.svelte';
	import { tg } from '../../../../lib/grammar-capture/messages';
	import { tr } from '../../../../lib/reader/messages';
	import OverrideList from '../../../../lib/reader/OverrideList.svelte';
	import TokenPopup from '../../../../lib/reader/TokenPopup.svelte';
	import { locationLabel, pieces, ReaderPage } from '../../../../lib/state/reader.svelte';

	const mediaId = $derived(page.params.id ?? '');
	const vm = new ReaderPage(() => mediaId);
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
</script>

<h1>{tr('rd.title')}</h1>

<div class="controls">
	<label>
		{tr('rd.segment')}
		<select bind:value={vm.segmentId}>
			<option value="">{tr('rd.allSegments')}</option>
			{#each vm.segments as s (s.id)}
				<option value={s.id}>{s.title || String(s.ordinal + 1)}</option>
			{/each}
		</select>
	</label>
	<label><input type="checkbox" bind:checked={vm.furigana} /> {tr('rd.furigana')}</label>
</div>

{#if vm.showBanner}
	<div class="banner" role="status">
		{tr('rd.banner')}
		<button type="button" onclick={() => vm.reparse()}>{tr('rd.reparse')}</button>
		<button type="button" onclick={() => vm.dismissBanner()}>{tr('rd.dismiss')}</button>
	</div>
{/if}

{#if !vm.loaded}
	<p>{tr('rd.loading')}</p>
{:else if vm.sentences.length === 0}
	<p>{tr('rd.empty')}</p>
{/if}

<div class="reader" lang="ja">
	{#each vm.sentences as s (s.id)}
		<p class="sentence">
			<small class="loc">{locationLabel(s)}</small>
			<button type="button" onclick={() => vm.toggleCapture(s.id)}>{tg('gc.open')}</button>
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
							if (e.key === 'Enter') vm.select(s.id, piece.tokenIndex ?? 0);
						}}
						>{#if vm.furigana && tok.reading && tok.reading !== piece.text}<ruby
								>{piece.text}<rt>{tok.reading}</rt></ruby
							>{:else}{piece.text}{/if}</span
					>
				{/if}
			{/each}
		</p>
		{#if vm.capturing === s.id}<GrammarCapture sentence={s} onclose={() => vm.closeCapture()} />{/if}
		{#if vm.selSentence && vm.selSentence.id === s.id && vm.selected}
			<TokenPopup
				sentence={s}
				tokenIndex={vm.selected.index}
				onclose={() => vm.clearSelection()}
				onoverride={() => vm.overridden()}
				onstatus={(seq) => void vm.resolve(seq)}
			/>
		{/if}
	{/each}
</div>

<div bind:this={sentinel}>
	{#if vm.cursor}<button type="button" onclick={() => vm.load(true)}>{tr('rd.more')}</button>{/if}
</div>

<OverrideList {mediaId} version={vm.overridesVersion} />

<style>
	.controls {
		display: flex;
		gap: var(--kb-space-3);
		margin-bottom: var(--kb-space-3);
	}
	.sentence {
		font-size: 1.25rem;
		line-height: 2;
	}
	.loc {
		margin-right: var(--kb-space-2);
		opacity: 0.6;
		font-size: 0.7rem;
	}
	.tok {
		cursor: pointer;
		border-bottom: 2px solid transparent;
	}
	.tok.new {
		border-bottom-color: #d33;
	}
	.tok.learning {
		border-bottom-color: #e90;
	}
	.tok.known,
	.tok.never_forget {
		border-bottom-color: #2a2;
	}
	.tok.ignored,
	.tok.blacklisted,
	.tok.unmapped {
		opacity: 0.7;
	}
	.tok.sel {
		background: rgba(100, 150, 255, 0.25);
	}
	.banner {
		border: 1px solid var(--kb-border, #888);
		padding: var(--kb-space-2);
		margin-bottom: var(--kb-space-3);
	}
</style>
