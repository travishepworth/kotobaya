<script lang="ts">
	import { page } from '$app/state';
	import { StruggleWordPage } from '../../../../lib/state/struggle.svelte';
	import { tg, tgEnum } from '../../messages';

	const noteId = $derived(page.params.noteId ?? '');
	const vm = new StruggleWordPage();

	$effect(() => {
		vm.load(noteId);
	});
</script>

<p><a href="/struggle">{tg('st.back')}</a></p>
{#if !vm.loaded}
	<p>{tg('st.loading')}</p>
{:else if !vm.word}
	<p>{tg('st.notFound')}</p>
{:else}
	<h1 lang="ja">{vm.word.entry?.headword ?? vm.word.note.fields['word'] ?? vm.word.noteId}</h1>
	{#if vm.word.entry}
		<p lang="ja">{vm.word.entry.headwordReading} · {vm.word.entry.glosses.join('; ')}</p>
	{/if}
	<p>
		<small>{tg('st.score', { n: vm.word.score.toFixed(1) })} · {tg('st.failures', { n: vm.word.failures14d })}</small>
	</p>
	<p>
		<a class="kb-btn" href="/review?drill=hot_words">{tg('st.extraDrill')}</a>
		<small>{tg('st.extraDrillNote')} {tg('st.drillNote')}</small>
	</p>

	<h2>{tg('st.mnemonic')}</h2>
	<textarea rows="3" bind:value={vm.mnemonic}></textarea>
	<p><button class="kb-btn" type="button" disabled={vm.busy} onclick={() => vm.saveMnemonic()}>{tg('st.mnemonicSave')}</button></p>

	<h2>{tg('st.sentences')}</h2>
	{#if vm.sentences.length === 0}
		<p>{tg('st.sentencesNone')}</p>
	{:else}
		<ul>
			{#each vm.sentences as s (s.id)}
				<li>
					<span lang="ja">{s.text}</span>
					<button class="kb-btn kb-btn-quiet" type="button" disabled={vm.busy} onclick={() => vm.makeCard(s)}>
						{tg('st.makeCard')}
					</button>
				</li>
			{/each}
		</ul>
	{/if}

	<h2>{tg('st.partners')}</h2>
	{#if vm.partners.length === 0}
		<p>{tg('st.partnersNone')}</p>
	{:else}
		<ul>
			{#each vm.partners as c (`${c.noteAId}/${c.noteBId}/${c.reason}`)}
				<li>
					<strong lang="ja">{vm.partner(c).fields['word'] ?? vm.partner(c).kanjiLiteral ?? vm.partner(c).id}</strong>
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
