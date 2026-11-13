<script lang="ts">
	import { apiConfig, ResponseError } from '../api/client';
	import { DictionaryApi, NotesApi, ParsingApi, WordsApi } from '../api/generated';
	import type { DictEntry, DictEntrySummary, Sentence } from '../api/generated';
	import { uuidv7 } from '../review/model';
	import { pushToast } from '../toasts.svelte';
	import { tr } from './messages';
	import type { ReaderMessageKey } from './messages';
	import { buildOverridePut, changeEdit, joinEdit, splitEdit } from './overrides';
	import type { OverrideEdit, OverrideScope } from './overrides';
	import { setCachedStatus } from './status';

	/** Built-in Vocab note type (contracts/db). */
	const VOCAB_NOTE_TYPE = '00000000-0000-7000-8000-000000000101';

	let {
		sentence,
		tokenIndex,
		onclose,
		onoverride,
		onstatus
	}: {
		sentence: Sentence;
		tokenIndex: number;
		onclose: () => void;
		onoverride: () => void;
		onstatus: (seq: number) => void;
	} = $props();

	const dict = new DictionaryApi(apiConfig);
	const words = new WordsApi(apiConfig);
	const notes = new NotesApi(apiConfig);
	const parsing = new ParsingApi(apiConfig);

	const token = $derived(sentence.tokens[tokenIndex]);
	let entry = $state<DictEntry | null>(null);
	let scope = $state<OverrideScope>('sentence');
	let splitOffset = $state(1);
	let query = $state('');
	let results = $state<DictEntrySummary[] | null>(null);
	let mineId = $state(uuidv7());

	$effect(() => {
		const seq = token?.seq ?? null;
		entry = null;
		results = null;
		query = '';
		splitOffset = 1;
		mineId = uuidv7();
		if (seq == null) return;
		let live = true;
		dict
			.getDictEntry({ seq })
			.then((e) => {
				if (live) entry = e;
			})
			.catch(() => {});
		return () => {
			live = false;
		};
	});

	const splitPreview = $derived(splitEdit(sentence.tokens, tokenIndex, splitOffset));
	const joinPreview = $derived(joinEdit(sentence.tokens, tokenIndex));

	function show(e: OverrideEdit | null): string {
		return e ? e.tokens.map((x) => x.surface).join(' | ') : '';
	}

	async function save(edit: OverrideEdit | null): Promise<void> {
		if (!edit) return;
		try {
			await parsing.putParseOverride({
				overrideId: uuidv7(),
				parseOverridePut: buildOverridePut(edit, scope, {
					mediaId: sentence.mediaId,
					sentenceId: sentence.id
				})
			});
			pushToast(tr('rd.saved'), 'info');
			onoverride();
		} catch {
			/* toast shown */
		}
	}

	async function search(): Promise<void> {
		if (!query.trim()) return;
		try {
			results = (await dict.searchDictionary({ q: query.trim(), limit: 10 })).items;
		} catch {
			/* toast shown */
		}
	}

	async function mark(status: 'known' | 'ignored' | 'never_forget'): Promise<void> {
		const seq = token?.seq;
		if (seq == null) return;
		try {
			await words.setWordStatus({ seq, wordStatusSet: { status } });
			setCachedStatus(seq, status);
			if (entry) entry = { ...entry, status };
			onstatus(seq);
			pushToast(tr('rd.marked'), 'info');
		} catch {
			/* toast shown */
		}
	}

	async function mine(): Promise<void> {
		const seq = token?.seq;
		if (seq == null) return;
		try {
			await notes.createNote({
				noteCreate: {
					id: mineId,
					noteTypeId: VOCAB_NOTE_TYPE,
					entrySeq: seq,
					sourceSentence: sentence.text,
					sourceSentenceId: sentence.id,
					sourceMediaId: sentence.mediaId
				}
			});
			pushToast(tr('rd.mined'), 'info');
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 409) {
				pushToast(tr('rd.duplicate'), 'info');
			}
		}
	}

	const statuses = ['rd.markKnown', 'rd.markIgnore', 'rd.markNeverForget'] as const satisfies ReaderMessageKey[];
	const statusValue = { 'rd.markKnown': 'known', 'rd.markIgnore': 'ignored', 'rd.markNeverForget': 'never_forget' } as const;
</script>

{#if token}
	<aside class="popup" aria-label={tr('rd.entry')}>
		<header>
			<strong lang="ja">{token.surface}</strong>
			{#if token.reading && token.reading !== token.surface}<span lang="ja">{token.reading}</span>{/if}
			<button type="button" onclick={onclose}>{tr('rd.close')}</button>
		</header>

		{#if token.seq == null}
			<p>{tr('rd.noEntry')}</p>
		{:else if entry}
			<p><span lang="ja">{entry.headword}</span> · {tr('rd.status')}: {tr(`rd.status.${entry.status}`)}</p>
			<ol>
				{#each entry.senses.slice(0, 4) as s, i (i)}
					<li>{s.gloss.map((g) => g.text).join('; ')}</li>
				{/each}
			</ol>
			<div class="row">
				<button type="button" onclick={mine}>{tr('rd.mine')}</button>
				{#each statuses as k (k)}
					<button type="button" onclick={() => mark(statusValue[k])}>{tr(k)}</button>
				{/each}
			</div>
		{/if}

		<fieldset>
			<legend>{tr('rd.fix')}</legend>
			<label>
				{tr('rd.scope')}
				<select bind:value={scope}>
					<option value="sentence">{tr('rd.scope.sentence')}</option>
					<option value="media">{tr('rd.scope.media')}</option>
					<option value="global">{tr('rd.scope.global')}</option>
				</select>
			</label>
			<div class="row">
				<label>
					{tr('rd.splitAt')}
					<input type="number" min="1" max={Math.max(1, token.surface.length - 1)} bind:value={splitOffset} />
				</label>
				<button type="button" disabled={!splitPreview} onclick={() => save(splitPreview)}>
					{tr('rd.split')}
				</button>
				<small>{show(splitPreview) || tr('rd.cannotSplit')}</small>
			</div>
			<div class="row">
				<button type="button" disabled={!joinPreview} onclick={() => save(joinPreview)}>
					{tr('rd.join')}
				</button>
				<small>{show(joinPreview) || tr('rd.noNext')}</small>
			</div>
			<form
				class="row"
				onsubmit={(e) => {
					e.preventDefault();
					void search();
				}}
			>
				<input type="search" placeholder={tr('rd.search')} aria-label={tr('rd.change')} bind:value={query} />
				<button type="submit">{tr('rd.change')}</button>
			</form>
			{#if results}
				{#if results.length === 0}
					<p>{tr('rd.noResults')}</p>
				{/if}
				<ul>
					{#each results as r (r.seq)}
						<li>
							<button
								type="button"
								title={tr('rd.pick')}
								onclick={() =>
									save(
										changeEdit(sentence.tokens, tokenIndex, {
											seq: r.seq,
											lemma: r.headword,
											reading: r.headwordReading
										})
									)}
							>
								<span lang="ja">{r.headword}</span> {r.glosses.slice(0, 2).join('; ')}
							</button>
						</li>
					{/each}
				</ul>
			{/if}
		</fieldset>
	</aside>
{/if}

<style>
	.popup {
		border: 1px solid var(--kb-border, #888);
		padding: var(--kb-space-3);
		margin: var(--kb-space-2) 0;
		background: var(--kb-surface, transparent);
	}
	header,
	.row {
		display: flex;
		gap: var(--kb-space-2);
		align-items: center;
		flex-wrap: wrap;
	}
	ul {
		list-style: none;
		padding: 0;
	}
</style>
