<script lang="ts">
	import { page } from '$app/state';
	import { MediaApi } from '../../../../lib/api/generated';
	import type { Confusable, HotWord, NoteSummary, Sentence } from '../../../../lib/api/generated';
	import { apiConfig, ResponseError } from '../../../../lib/api/client';
	import { notesApi } from '../../../../lib/notes/api';
	import { wordsApi } from '../../../../lib/review/api';
	import { uuidv7 } from '../../../../lib/review/model';
	import { buildSentenceNote } from '../../../../lib/sentences/noteRequest';
	import { pushToast } from '../../../../lib/toasts.svelte';
	import { tg, tgEnum } from '../../messages';

	const media = new MediaApi(apiConfig);
	const noteId = $derived(page.params.noteId ?? '');

	let word = $state<HotWord | null>(null);
	let sentences = $state<Sentence[]>([]);
	let partners = $state<Confusable[]>([]);
	let mnemonic = $state('');
	let loaded = $state(false);
	let busy = $state(false);

	$effect(() => {
		const id = noteId;
		void (async () => {
			try {
				const hot = (await wordsApi.listHotWords({ limit: 200 })).items.find((w) => w.noteId === id) ?? null;
				word = hot;
				if (hot) {
					mnemonic = hot.note.mnemonic;
					if (hot.entrySeq !== null) {
						const [s, c] = await Promise.all([
							media.listSentences({ entrySeq: hot.entrySeq, limit: 10 }),
							wordsApi.listWordConfusables({ seq: hot.entrySeq })
						]);
						sentences = s.items;
						partners = c.items;
					}
				}
			} catch {
				/* toast shown */
			} finally {
				loaded = true;
			}
		})();
	});

	async function makeCard(s: Sentence): Promise<void> {
		busy = true;
		try {
			await notesApi.createNote({ noteCreate: buildSentenceNote(s, { id: uuidv7() }) });
			pushToast(tg('st.cardCreated'), 'info');
		} catch (e) {
			if (e instanceof ResponseError && e.response.status === 409) pushToast(tg('st.duplicate'), 'info');
		} finally {
			busy = false;
		}
	}

	async function saveMnemonic(): Promise<void> {
		busy = true;
		try {
			await notesApi.updateNote({ noteId, notePatch: { mnemonic } });
			pushToast(tg('st.mnemonicSaved'), 'info');
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}

	function partner(c: Confusable): NoteSummary {
		return c.noteAId === noteId ? c.noteB : c.noteA;
	}
</script>

<p><a href="/struggle">{tg('st.back')}</a></p>
{#if !loaded}
	<p>{tg('st.loading')}</p>
{:else if !word}
	<p>{tg('st.notFound')}</p>
{:else}
	<h1 lang="ja">{word.entry?.headword ?? word.note.fields['word'] ?? word.noteId}</h1>
	{#if word.entry}
		<p lang="ja">{word.entry.headwordReading} · {word.entry.glosses.join('; ')}</p>
	{/if}
	<p>
		<small>{tg('st.score', { n: word.score.toFixed(1) })} · {tg('st.failures', { n: word.failures14d })}</small>
	</p>
	<p>
		<a class="kb-btn" href="/review?drill=hot_words">{tg('st.extraDrill')}</a>
		<small>{tg('st.extraDrillNote')} {tg('st.drillNote')}</small>
	</p>

	<h2>{tg('st.mnemonic')}</h2>
	<textarea rows="3" bind:value={mnemonic}></textarea>
	<p><button class="kb-btn" type="button" disabled={busy} onclick={saveMnemonic}>{tg('st.mnemonicSave')}</button></p>

	<h2>{tg('st.sentences')}</h2>
	{#if sentences.length === 0}
		<p>{tg('st.sentencesNone')}</p>
	{:else}
		<ul>
			{#each sentences as s (s.id)}
				<li>
					<span lang="ja">{s.text}</span>
					<button class="kb-btn kb-btn-quiet" type="button" disabled={busy} onclick={() => makeCard(s)}>
						{tg('st.makeCard')}
					</button>
				</li>
			{/each}
		</ul>
	{/if}

	<h2>{tg('st.partners')}</h2>
	{#if partners.length === 0}
		<p>{tg('st.partnersNone')}</p>
	{:else}
		<ul>
			{#each partners as c (`${c.noteAId}/${c.noteBId}/${c.reason}`)}
				<li>
					<strong lang="ja">{partner(c).fields['word'] ?? partner(c).kanjiLiteral ?? partner(c).id}</strong>
					<small>{tgEnum('st.reason', c.reason)} · {tgEnum('st.status', c.status)}</small>
				</li>
			{/each}
		</ul>
	{/if}
{/if}

<style>
	small {
		color: var(--kb-text-muted);
	}
	li {
		margin-bottom: var(--kb-space-2);
	}
	textarea {
		width: 100%;
	}
</style>
