<script lang="ts">
	// Query box: client-side validation and completion come from the shared core (../core/index).
	// The server stays the source of truth for results; its 422 is shown via `serverError`.
	import { completeQuery, parseQuery, type SearchVocabulary } from '../state/search';
	import { searchErrorText } from '../decks/messages';
	import { tc } from './messages';

	let {
		value = $bindable(),
		vocabulary,
		serverError = null,
		onsubmit
	}: { value: string; vocabulary: SearchVocabulary; serverError?: string | null; onsubmit: () => void } = $props();

	let cursor = $state(0);
	let input: HTMLInputElement | undefined = $state();

	const parsed = $derived(parseQuery(value));
	const completions = $derived(
		value === '' ? [] : completeQuery(value, Math.min(cursor, value.length), vocabulary, 8)
	);

	function track(): void {
		cursor = input?.selectionStart ?? value.length;
	}

	function apply(c: { insert: string; replaceStart: number; replaceEnd: number }): void {
		value = value.slice(0, c.replaceStart) + c.insert + value.slice(c.replaceEnd);
		const at = c.replaceStart + c.insert.length;
		cursor = at;
		queueMicrotask(() => {
			input?.focus();
			input?.setSelectionRange(at, at);
		});
	}
</script>

<form
	onsubmit={(e) => {
		e.preventDefault();
		onsubmit();
	}}
>
	<label>
		{tc('cards.query')}
		<span class="row">
			<input
				type="text"
				spellcheck="false"
				autocomplete="off"
				bind:this={input}
				bind:value
				oninput={track}
				onkeyup={track}
				onclick={track}
				aria-invalid={!parsed.ok}
				aria-describedby="cards-query-status"
			/>
			<button type="submit" class="kb-btn" disabled={!parsed.ok}>{tc('cards.search')}</button>
		</span>
	</label>
	<p class="muted">{tc('cards.queryHelp')}</p>
	<div id="cards-query-status" role="status">
		{#if value.trim() === ''}
			<!-- empty = all cards -->
		{:else if parsed.ok}
			<p class="muted">{tc('cards.queryOk')}</p>
		{:else}
			<ul class="errors">
				{#each parsed.errors as err, i (i)}
					<li>
						<mark>{value.slice(0, err.start)}</mark><!--
						--><strong class="bad">{value.slice(err.start, err.end) || '⏎'}</strong>
						{searchErrorText(err.messageKey)} ({err.start})
					</li>
				{/each}
			</ul>
		{/if}
		{#if serverError}<p class="errors">{tc('cards.queryServerRejected', { detail: serverError })}</p>{/if}
	</div>
	{#if completions.length > 0}
		<div class="suggest" aria-label={tc('cards.suggestions')}>
			{#each completions as c, i (i)}
				<button type="button" class="kb-btn kb-btn-quiet" onclick={() => apply(c)}>{c.label}</button>
			{/each}
		</div>
	{/if}
</form>

<style>
	label {
		display: grid;
		gap: var(--kb-space-1);
	}
	.row {
		display: flex;
		gap: var(--kb-space-2);
	}
	input {
		flex: 1;
		font-family: var(--kb-font-mono);
	}
	.muted {
		color: var(--kb-text-muted);
		margin: 0;
	}
	.errors {
		color: var(--kb-error);
		margin: 0;
		padding-left: var(--kb-space-4);
	}
	mark {
		background: none;
		color: var(--kb-text-muted);
		font-family: var(--kb-font-mono);
	}
	.bad {
		font-family: var(--kb-font-mono);
		text-decoration: underline wavy var(--kb-error);
	}
	.suggest {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1);
	}
</style>
