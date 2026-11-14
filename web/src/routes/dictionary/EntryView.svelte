<script lang="ts">
	// A dictionary entry, set for reading: headword ▸ reading + pitch ▸ status and actions ▸ senses / kanji / examples.
	import Button from '../../lib/ui/Button.svelte';
	import { noteHref } from '../../lib/state/noteLink';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import GrammarCapture from '../../lib/ui/GrammarCapture.svelte';
	import PitchAccent from '../../lib/ui/PitchAccent.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import Tabs from '../../lib/ui/Tabs.svelte';
	import { splitMorae } from '../../lib/ui/kinds';
	import type { DictEntry, DictionaryPage } from '../../lib/state/dictionary.svelte';
	import KanjiDetail from './KanjiDetail.svelte';
	import { dx, type DictViewKey } from './messages';

	interface Props {
		vm: DictionaryPage;
		entry: DictEntry;
	}
	let { vm, entry }: Props = $props();

	type Section = 'senses' | 'kanji' | 'examples';
	let section = $state<Section>('senses');
	// A new entry opens on its senses.
	$effect(() => {
		void entry.seq;
		section = 'senses';
	});

	const kana = $derived(entry.headword === entry.headwordReading);
	const exampleCount = $derived(vm.examples ? vm.examples.media.length + vm.examples.tatoeba.length : 0);
	const otherForms = $derived(entry.kanjiForms.map((k) => k.text).filter((t) => t !== entry.headword));
	const otherReadings = $derived(entry.readings.map((r) => r.text).filter((t) => t !== entry.headwordReading));
	const pitches = $derived(entry.pitch.flatMap((p) => p.positions.map((pos) => ({ reading: p.reading, pos }))).slice(0, 3));
	const capturing = $derived(vm.examples?.media.find((s) => s.id === vm.capturing) ?? null);

	function openKanji(k: string): void {
		if (vm.kanjiOpen !== k) vm.toggleKanji(k);
	}
	$effect(() => {
		// The kanji tab opens on the first kanji.
		if (section === 'kanji' && vm.kanjiOpen === null && entry.kanji.length > 0) vm.toggleKanji(entry.kanji[0]);
	});
</script>

<article class="entry" aria-labelledby="entry-hw">
	<header class="head">
		<div class="words">
			<h2 id="entry-hw" class="hw" lang="ja">{entry.headword}</h2>
			{#if !kana || pitches.length > 0}
				<div class="sub">
					{#if !kana && pitches.length === 0}<span class="reading" lang="ja">{entry.headwordReading}</span>{/if}
					{#each pitches as p (p.reading + p.pos)}
						<PitchAccent morae={splitMorae(p.reading)} downstep={p.pos} />
					{/each}
				</div>
			{/if}
		</div>
		<div class="actions">
			{#if entry.noteId === null}
				<Button variant="tonal" icon="plus" loading={vm.adding} onclick={() => void vm.addNote(entry)}>{dx('dx.addNote')}</Button>
			{:else}
				<span class="in"><span class="dot" aria-hidden="true"></span>{dx('dx.inNotes')}</span>
				<Button variant="quiet" href={noteHref(entry.noteId)} iconEnd="chevronRight">{dx('dx.openNote')}</Button>
			{/if}
		</div>
		<p class="tags">
			{#if entry.isCommon}<span class="tag strong">{dx('dx.common')}</span>{/if}
			{#if entry.freqRank !== null}<span class="tag" title={dx('dx.freqLong', { n: entry.freqRank })}>{dx('dx.freqLong', { n: entry.freqRank.toLocaleString() })}</span>{/if}
			{#if entry.status !== 'new'}<span class="tag">{dx(`dx.status.${entry.status}` as DictViewKey)}</span>{/if}
			{#if entry.retired}<span class="tag warn">{dx('dx.retired')}</span>{/if}
		</p>
	</header>

	<Tabs
		label={dx('dx.tabs')}
		bind:value={section}
		tabs={[
			{ value: 'senses', label: dx('dx.tab.senses'), count: entry.senses.length, controls: 'entry-panel' },
			{ value: 'kanji', label: dx('dx.tab.kanji'), count: entry.kanji.length, controls: 'entry-panel' },
			{ value: 'examples', label: dx('dx.tab.examples'), count: vm.examples ? exampleCount : undefined, controls: 'entry-panel' }
		]}
	/>

	<div class="panel" id="entry-panel" role="tabpanel">
		{#if section === 'senses'}
			<ol class="senses">
				{#each entry.senses as s, i (i)}
					<li>
						<span class="num" aria-hidden="true">{i + 1}</span>
						<div class="sense">
							{#if s.pos.length > 0}<p class="pos">{s.pos.join(' · ')}</p>{/if}
							<p class="gloss">{s.gloss.map((g) => g.text).join('; ')}</p>
							{#if s.info.length > 0}<p class="info">{s.info.join('; ')}</p>{/if}
							{#if s.misc.length + s.field.length + s.dialect.length > 0}
								<p class="misc">
									{#each [...s.misc, ...s.field, ...s.dialect] as m (m)}<span>{m}</span>{/each}
								</p>
							{/if}
						</div>
					</li>
				{/each}
			</ol>
			{#if otherForms.length > 0 || otherReadings.length > 0}
				<dl class="forms">
					{#if otherForms.length > 0}<div><dt>{dx('dx.otherForms')}</dt><dd lang="ja">{otherForms.join('、')}</dd></div>{/if}
					{#if otherReadings.length > 0}<div><dt>{dx('dx.readings')}</dt><dd lang="ja">{otherReadings.join('、')}</dd></div>{/if}
				</dl>
			{/if}
		{:else if section === 'kanji'}
			{#if entry.kanji.length === 0}
				<EmptyState art="kanji" title={dx('dx.kanjiNoWords')} />
			{:else}
				<div class="tiles" role="group" aria-label={dx('dx.kanjiTiles')}>
					{#each entry.kanji as k (k)}
						<button
							type="button"
							class="tile"
							lang="ja"
							aria-pressed={vm.kanjiOpen === k}
							aria-label={dx('dx.kanjiWords', { k })}
							onclick={() => openKanji(k)}>{k}</button
						>
					{/each}
				</div>
				{#if vm.kanjiOpen !== null}
					<KanjiDetail literal={vm.kanjiOpen} onopen={(seq) => void vm.open(seq)} />
				{/if}
			{/if}
		{:else if !vm.examples}
			<Skeleton shape="text" count={4} />
		{:else if exampleCount === 0}
			<EmptyState art="media" title={dx('dx.examplesNone')} />
		{:else}
			{#if vm.examples.media.length > 0}
				<h3 class="group">{dx('dx.examplesMedia')}</h3>
				<ul class="examples">
					{#each vm.examples.media as s (s.id)}
						<li>
							<p class="jp" lang="ja">{s.text}</p>
							<Button variant="quiet" size="sm" onclick={() => vm.toggleCapture(s.id)}>{dx('dx.capture')}</Button>
						</li>
					{/each}
				</ul>
			{/if}
			{#if vm.examples.tatoeba.length > 0}
				<h3 class="group">{dx('dx.examplesTatoeba')}</h3>
				<ul class="examples">
					{#each vm.examples.tatoeba as s (s.id)}
						<li>
							<p class="jp" lang="ja">{s.text}</p>
							{#if s.translation}<p class="en">{s.translation}</p>{/if}
						</li>
					{/each}
				</ul>
			{/if}
		{/if}
	</div>
</article>

{#if capturing}
	<GrammarCapture sentence={capturing} onclose={() => vm.closeCapture()} />
{/if}

<style>
	.entry {
		container-type: inline-size;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
		max-width: var(--kb-size-measure);
	}
	.head {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		grid-template-areas: 'words actions' 'tags tags';
		gap: var(--kb-space-4);
		align-items: start;
	}
	.words {
		grid-area: words;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		min-width: 0;
	}
	.hw {
		font: var(--kb-type-jp-display);
		letter-spacing: 0.02em;
		word-break: auto-phrase;
	}
	.sub {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2) var(--kb-space-5);
	}
	.reading {
		font: var(--kb-type-reading);
		color: var(--kb-color-text-muted);
	}
	.actions {
		grid-area: actions;
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: flex-end;
		gap: var(--kb-space-2);
	}
	.in {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-2);
		padding: var(--kb-space-1) var(--kb-space-3);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-state-review-container);
		color: var(--kb-color-state-review);
		font: var(--kb-type-label);
	}
	.in .dot {
		width: 0.5rem;
		height: 0.5rem;
		border-radius: 50%;
		background: currentColor;
	}
	.tags {
		grid-area: tags;
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
	}
	.tags:empty {
		display: none;
	}
	.tag {
		padding: 2px var(--kb-space-2);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-full);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.tag.strong {
		border-color: transparent;
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
	}
	.tag.warn {
		border-color: transparent;
		background: var(--kb-color-warning-container);
		color: var(--kb-color-on-warning-container);
	}
	@container (max-width: 36rem) {
		.head {
			grid-template-columns: minmax(0, 1fr);
			grid-template-areas: 'words' 'tags' 'actions';
		}
		.actions {
			justify-content: flex-start;
		}
	}

	/* ---------- senses ---------- */
	.senses {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.senses li {
		display: grid;
		grid-template-columns: 2rem minmax(0, 1fr);
		gap: var(--kb-space-2);
	}
	.num {
		display: grid;
		place-items: center;
		width: 1.75rem;
		height: 1.75rem;
		border-radius: 50%;
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-caption);
		font-weight: 600;
		color: var(--kb-color-text-muted);
	}
	.sense {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		min-width: 0;
	}
	.pos {
		font: var(--kb-type-overline);
		letter-spacing: var(--kb-type-overline-tracking);
		text-transform: uppercase;
		color: var(--kb-color-text-muted);
	}
	.gloss {
		font: var(--kb-type-body-lg);
		overflow-wrap: anywhere;
	}
	.info {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.misc {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1);
	}
	.misc span {
		padding: 0 var(--kb-space-2);
		border-radius: var(--kb-radius-xs);
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.forms {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		margin: var(--kb-space-6) 0 0;
		padding-top: var(--kb-space-4);
		border-top: 1px solid var(--kb-color-border);
	}
	.forms div {
		display: grid;
		grid-template-columns: 8rem minmax(0, 1fr);
		gap: var(--kb-space-3);
	}
	.forms dt {
		font: var(--kb-type-label);
		color: var(--kb-color-text-muted);
	}
	.forms dd {
		margin: 0;
		font: var(--kb-type-jp-body);
	}

	/* ---------- kanji ---------- */
	.tiles {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		margin-bottom: var(--kb-space-6);
	}
	.tile {
		display: grid;
		place-items: center;
		width: 4rem;
		height: 4rem;
		border: 1px solid var(--kb-color-border-strong);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface);
		color: var(--kb-color-text);
		font: 2.25rem/1 var(--kb-family-serif);
		cursor: pointer;
		transition: background-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.tile:hover {
		background: var(--kb-color-surface-hover);
	}
	.tile[aria-pressed='true'] {
		border-color: var(--kb-color-primary);
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
		box-shadow: inset 0 0 0 1px var(--kb-color-primary);
	}

	/* ---------- examples ---------- */
	.group {
		margin: var(--kb-space-2) 0 var(--kb-space-2);
		font: var(--kb-type-overline);
		letter-spacing: var(--kb-type-overline-tracking);
		text-transform: uppercase;
		color: var(--kb-color-text-muted);
	}
	.examples {
		display: flex;
		flex-direction: column;
		margin: 0 0 var(--kb-space-5);
		padding: 0;
		list-style: none;
	}
	.examples li {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		justify-content: space-between;
		gap: var(--kb-space-1) var(--kb-space-3);
		padding: var(--kb-space-3) 0;
		border-bottom: 1px solid var(--kb-color-border);
	}
	.jp {
		flex: 1 1 18rem;
		font: var(--kb-type-jp-body);
	}
	.en {
		flex-basis: 100%;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
</style>
