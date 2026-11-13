<script lang="ts">
	// Smart-deck query field: validated and completed by the shared core (parseQuery,
	// completeQuery via ../core/index); nothing about the search language is decided here.
	import { completeQuery, parseQuery, type SearchVocabulary } from '../state/search';
	import { searchErrorText, td } from './messages';

	let {
		value = $bindable(),
		vocabulary,
		serverError = null
	}: { value: string; vocabulary: SearchVocabulary; serverError?: string | null } = $props();

	let cursor = $state(0);
	let input: HTMLTextAreaElement | undefined = $state();

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

<label>
	{td('decks.query')}
	<textarea
		rows="2"
		spellcheck="false"
		bind:this={input}
		bind:value
		oninput={track}
		onkeyup={track}
		onclick={track}
		aria-invalid={!parsed.ok}
		aria-describedby="query-status"
	></textarea>
</label>
<p class="help">{td('decks.queryHelp')}</p>
<div id="query-status" role="status">
	{#if value.trim() === ''}
		<!-- nothing to validate yet -->
	{:else if parsed.ok}
		<p class="ok">{td('decks.queryOk')}</p>
	{:else}
		<ul class="errors">
			{#each parsed.errors as err, i (i)}
				<li>
					<code>{value.slice(err.start, err.end)}</code>
					{searchErrorText(err.messageKey)}
				</li>
			{/each}
		</ul>
	{/if}
	{#if serverError}<p class="errors">{td('decks.queryServerRejected', { detail: serverError })}</p>{/if}
</div>
{#if completions.length > 0}
	<div class="suggest" aria-label={td('decks.suggestions')}>
		{#each completions as c, i (i)}
			<button type="button" class="kb-btn kb-btn-quiet" onclick={() => apply(c)}>{c.label}</button>
		{/each}
	</div>
{/if}

<style>
	label {
		display: grid;
		gap: var(--kb-space-1);
	}
	textarea {
		font-family: var(--kb-font-mono);
		width: 100%;
	}
	.help {
		color: var(--kb-text-muted);
		margin: 0;
	}
	.ok {
		color: var(--kb-text-muted);
		margin: 0;
	}
	.errors {
		color: var(--kb-error);
		margin: 0;
	}
	.suggest {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1);
	}
</style>
