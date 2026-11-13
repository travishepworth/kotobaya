<script lang="ts">
	import { page } from '$app/state';
	import { apiConfig } from '../../../../lib/api/client';
	import { MediaApi } from '../../../../lib/api/generated';
	import type { MediaSegment, Sentence, WordStatusValue } from '../../../../lib/api/generated';
	import GrammarCapture from '../../../../lib/grammar-capture/GrammarCapture.svelte';
	import { tg } from '../../../../lib/grammar-capture/messages';
	import { pushToast } from '../../../../lib/toasts.svelte';
	import { formatMs, pieces } from '../../../../lib/reader/format';
	import { tr } from '../../../../lib/reader/messages';
	import OverrideList from '../../../../lib/reader/OverrideList.svelte';
	import { cachedStatus, loadStatus } from '../../../../lib/reader/status';
	import TokenPopup from '../../../../lib/reader/TokenPopup.svelte';

	const api = new MediaApi(apiConfig);
	const mediaId = $derived(page.params.id ?? '');

	let segments = $state<MediaSegment[]>([]);
	let segmentId = $state('');
	let sentences = $state<Sentence[]>([]);
	let cursor = $state<string | null>(null);
	let loaded = $state(false);
	let furigana = $state(false);
	let capturing = $state<string | null>(null);
	let selected = $state<{ sentenceId: string; index: number } | null>(null);
	let overridesVersion = $state(0);
	let showBanner = $state(false);
	let statuses = $state<Record<number, WordStatusValue>>({});
	let sentinel = $state<HTMLElement | null>(null);
	let busy = false;
	let gen = 0;

	$effect(() => {
		const id = mediaId;
		api
			.getMedia({ mediaId: id })
			.then((m) => {
				segments = m.segments ?? [];
			})
			.catch(() => {});
	});

	async function load(more: boolean): Promise<void> {
		if (busy) return;
		busy = true;
		const mine = more ? gen : ++gen;
		try {
			const p = await api.listMediaSentences({
				mediaId,
				segmentId: segmentId || undefined,
				limit: 50,
				cursor: more ? (cursor ?? undefined) : undefined
			});
			if (mine !== gen) return;
			sentences = more ? [...sentences, ...p.items] : p.items;
			cursor = p.nextCursor;
			for (const s of p.items) {
				for (const t of s.tokens) {
					if (t.seq != null && t._class === 'content') void resolve(t.seq);
				}
			}
		} catch {
			/* toast shown */
		} finally {
			loaded = true;
			busy = false;
		}
	}

	async function resolve(seq: number): Promise<void> {
		const s = cachedStatus(seq) ?? (await loadStatus(seq));
		if (s) statuses[seq] = s;
	}

	$effect(() => {
		void mediaId;
		void segmentId;
		loaded = false;
		selected = null;
		void load(false);
	});

	$effect(() => {
		const el = sentinel;
		if (!el || typeof IntersectionObserver === 'undefined') return;
		const io = new IntersectionObserver((entries) => {
			if (entries.some((e) => e.isIntersecting) && cursor) void load(true);
		});
		io.observe(el);
		return () => io.disconnect();
	});

	function locationLabel(s: Sentence): string {
		if (s.startMs != null) return tr('rd.at', { time: formatMs(s.startMs) });
		const p = s.location?.['page'];
		if (typeof p === 'number' || typeof p === 'string') return tr('rd.page', { page: p });
		return '';
	}

	function statusClass(seq: number | null): string {
		if (seq == null) return 'unmapped';
		return statuses[seq] ?? 'pending';
	}

	async function reparse(): Promise<void> {
		try {
			await api.reparseMedia({ mediaId });
			showBanner = false;
			pushToast(tr('rd.reparseStarted'), 'info');
		} catch {
			/* toast shown */
		}
	}

	const selSentence = $derived(sentences.find((s) => s.id === selected?.sentenceId) ?? null);
</script>

<h1>{tr('rd.title')}</h1>

<div class="controls">
	<label>
		{tr('rd.segment')}
		<select bind:value={segmentId}>
			<option value="">{tr('rd.allSegments')}</option>
			{#each segments as s (s.id)}
				<option value={s.id}>{s.title || String(s.ordinal + 1)}</option>
			{/each}
		</select>
	</label>
	<label><input type="checkbox" bind:checked={furigana} /> {tr('rd.furigana')}</label>
</div>

{#if showBanner}
	<div class="banner" role="status">
		{tr('rd.banner')}
		<button type="button" onclick={reparse}>{tr('rd.reparse')}</button>
		<button type="button" onclick={() => (showBanner = false)}>{tr('rd.dismiss')}</button>
	</div>
{/if}

{#if !loaded}
	<p>{tr('rd.loading')}</p>
{:else if sentences.length === 0}
	<p>{tr('rd.empty')}</p>
{/if}

<div class="reader" lang="ja">
	{#each sentences as s (s.id)}
		<p class="sentence">
			<small class="loc">{locationLabel(s)}</small>
			<button type="button" onclick={() => (capturing = capturing === s.id ? null : s.id)}>{tg('gc.open')}</button>
			{#each pieces(s) as piece, i (i)}
				{#if piece.tokenIndex === null}{piece.text}{:else}
					{@const tok = s.tokens[piece.tokenIndex]}
					<span
						class="tok {statusClass(tok.seq)}"
						class:sel={selected?.sentenceId === s.id && selected.index === piece.tokenIndex}
						role="button"
						tabindex="0"
						onclick={() => (selected = { sentenceId: s.id, index: piece.tokenIndex ?? 0 })}
						onkeydown={(e) => {
							if (e.key === 'Enter') selected = { sentenceId: s.id, index: piece.tokenIndex ?? 0 };
						}}
						>{#if furigana && tok.reading && tok.reading !== piece.text}<ruby
								>{piece.text}<rt>{tok.reading}</rt></ruby
							>{:else}{piece.text}{/if}</span
					>
				{/if}
			{/each}
		</p>
		{#if capturing === s.id}<GrammarCapture sentence={s} onclose={() => (capturing = null)} />{/if}
		{#if selSentence && selSentence.id === s.id && selected}
			<TokenPopup
				sentence={s}
				tokenIndex={selected.index}
				onclose={() => (selected = null)}
				onoverride={() => {
					overridesVersion++;
					showBanner = true;
					selected = null;
				}}
				onstatus={(seq) => void resolve(seq)}
			/>
		{/if}
	{/each}
</div>

<div bind:this={sentinel}>
	{#if cursor}<button type="button" onclick={() => load(true)}>{tr('rd.more')}</button>{/if}
</div>

<OverrideList {mediaId} version={overridesVersion} />

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
