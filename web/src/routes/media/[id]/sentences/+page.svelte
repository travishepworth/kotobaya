<script lang="ts">
	import { page } from '$app/state';
	import { ts } from '../../../../lib/sentences/messages';
	import GrammarCapture from '../../../../lib/grammar-capture/GrammarCapture.svelte';
	import { tg } from '../../../../lib/grammar-capture/messages';
	import { formatMs, pieces, playBlob, SentencesPage } from '../../../../lib/state/reader.svelte';

	const mediaId = $derived(page.params.id ?? '');
	const vm = new SentencesPage(() => mediaId);
</script>

<h1>{ts('sn.title')}</h1>

<div class="controls">
	<label>
		{ts('sn.filter')}
		<select bind:value={vm.filter}>
			<option value="all">{ts('sn.filter.all')}</option>
			<option value="i+1">{ts('sn.filter.i1')}</option>
			<option value="i+0">{ts('sn.filter.i0')}</option>
		</select>
	</label>
	<label>
		{ts('sn.segment')}
		<select bind:value={vm.segmentId}>
			<option value="">{ts('sn.allSegments')}</option>
			{#each vm.segments as s (s.id)}
				<option value={s.id}>{s.title || String(s.ordinal + 1)}</option>
			{/each}
		</select>
	</label>
	<input type="search" placeholder={ts('sn.search')} aria-label={ts('sn.search')} bind:value={vm.q} />
</div>

{#if !vm.loaded}
	<p>{ts('sn.loading')}</p>
{:else if vm.rows.length === 0}
	<p>{ts('sn.empty')}</p>
{/if}

<ul class="rows" lang="ja">
	{#each vm.rows as s (s.id)}
		<li>
			<span class="sentence">
				{#each pieces(s) as piece, i (i)}
					{#if piece.tokenIndex !== null && s.tokens[piece.tokenIndex].seq != null && s.tokens[piece.tokenIndex].seq === vm.unknown[s.id]}
						<mark>{piece.text}</mark>
					{:else}{piece.text}{/if}
				{/each}
			</span>
			{#if s.startMs != null}<small>{ts('sn.at', { time: formatMs(s.startMs) })}</small>{/if}
			{#if s.audioBlobHash}
				{@const hash = s.audioBlobHash}
				<button type="button" onclick={() => playBlob(hash)}>{ts('sn.play')}</button>
			{/if}
			<button type="button" onclick={() => vm.open(s)}>{ts('sn.make')}</button>
			<button type="button" onclick={() => vm.toggleCapture(s.id)}>{tg('gc.open')}</button>
			{#if vm.capturing === s.id}<GrammarCapture sentence={s} onclose={() => vm.closeCapture()} />{/if}
			{#if vm.editing?.id === s.id}
				<form
					class="card-form"
					onsubmit={(e) => {
						e.preventDefault();
						void vm.create();
					}}
				>
					<label>{ts('sn.form.sentence')} <input bind:value={vm.formSentence} /></label>
					<label>{ts('sn.form.reading')} <input bind:value={vm.formReading} /></label>
					<label>{ts('sn.form.translation')} <input bind:value={vm.formTranslation} /></label>
					<label>
						{ts('sn.form.deck')}
						<select bind:value={vm.formDeck}>
							<option value="">{ts('sn.form.defaultDeck')}</option>
							{#each vm.decks as d (d.id)}<option value={d.id}>{d.name}</option>{/each}
						</select>
					</label>
					{#if vm.unknown[s.id] != null}
						<label><input type="checkbox" bind:checked={vm.alsoVocab} /> {ts('sn.form.alsoVocab')}</label>
					{/if}
					<button type="submit">{ts('sn.form.create')}</button>
					<button type="button" onclick={() => vm.cancel()}>{ts('sn.form.cancel')}</button>
				</form>
			{/if}
		</li>
	{/each}
</ul>

{#if vm.cursor}<button type="button" onclick={() => vm.load(true)}>{ts('sn.more')}</button>{/if}

<style>
	.controls {
		display: flex;
		gap: var(--kb-space-3);
		margin-bottom: var(--kb-space-3);
		flex-wrap: wrap;
	}
	.rows {
		list-style: none;
		padding: 0;
	}
	.rows li {
		margin-bottom: var(--kb-space-3);
	}
	.sentence {
		font-size: 1.15rem;
	}
	.card-form {
		display: grid;
		gap: var(--kb-space-2);
		margin-top: var(--kb-space-2);
	}
</style>
