<script lang="ts">
	// The card search-language box: monospace, validated and completed by the shared core as you type. Errors point
	// at the offending span; completions are chips under the field. Used by Browse and smart decks.
	import Icon from './Icon.svelte';
	import { searchErrorText } from '../decks/messages';
	import { completeQuery, parseQuery, type SearchVocabulary } from '../state/search';

	interface Props {
		label: string;
		hideLabel?: boolean;
		value: string;
		vocabulary: SearchVocabulary;
		placeholder?: string;
		hint?: string;
		/** Server-side rejection of the last query. */
		serverError?: string | null;
		completionsLabel: string;
		onsubmit?: () => void;
		/** Rendered inside the field on the right (e.g. a search button). */
		multiline?: boolean;
	}
	let {
		label,
		hideLabel = false,
		value = $bindable(),
		vocabulary,
		placeholder,
		hint,
		serverError = null,
		completionsLabel,
		onsubmit,
		multiline = false
	}: Props = $props();

	const uid = $props.id();
	let cursor = $state(0);
	let input: HTMLInputElement | HTMLTextAreaElement | undefined = $state();
	let focused = $state(false);
	const parsed = $derived(parseQuery(value));
	const completions = $derived(value === '' || !focused ? [] : completeQuery(value, Math.min(cursor, value.length), vocabulary, 8));

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

	function onkeydown(e: KeyboardEvent): void {
		if (e.key === 'Enter' && !e.shiftKey && onsubmit) {
			e.preventDefault();
			if (parsed.ok) onsubmit();
		}
	}
</script>

<div class="field" class:invalid={!parsed.ok || !!serverError}>
	<label class="label" class:kb-sr={hideLabel} for="q-{uid}">{label}</label>
	<div class="box">
		<span class="icon"><Icon name="search" size={20} /></span>
		{#if multiline}
			<textarea
				id="q-{uid}"
				bind:this={input}
				bind:value
				rows={3}
				spellcheck="false"
				{placeholder}
				aria-invalid={!parsed.ok}
				aria-describedby="q-{uid}-status"
				oninput={track}
				onkeyup={track}
				onclick={track}
				onfocus={() => (focused = true)}
				onblur={() => setTimeout(() => (focused = false), 150)}
			></textarea>
		{:else}
			<input
				id="q-{uid}"
				type="search"
				bind:this={input}
				bind:value
				spellcheck="false"
				autocomplete="off"
				enterkeyhint="search"
				{placeholder}
				aria-invalid={!parsed.ok}
				aria-describedby="q-{uid}-status"
				oninput={track}
				onkeyup={track}
				onclick={track}
				{onkeydown}
				onfocus={() => (focused = true)}
				onblur={() => setTimeout(() => (focused = false), 150)}
			/>
		{/if}
	</div>
	<div id="q-{uid}-status" class="status" role="status">
		{#if !parsed.ok}
			{#each parsed.errors.slice(0, 2) as err, i (i)}
				<p class="msg error">
					<Icon name="alert" size={16} />
					<span>
						<code><span class="ok">{value.slice(Math.max(0, err.start - 12), err.start)}</span><span class="bad">{value.slice(err.start, err.end) || '⏎'}</span></code>
						{searchErrorText(err.messageKey)}
					</span>
				</p>
			{/each}
		{:else if serverError}
			<p class="msg error"><Icon name="alert" size={16} /><span>{serverError}</span></p>
		{:else if hint}
			<p class="msg">{hint}</p>
		{/if}
	</div>
	{#if completions.length > 0}
		<div class="suggest" role="group" aria-label={completionsLabel}>
			{#each completions as c, i (i)}
				<button type="button" onmousedown={(e) => e.preventDefault()} onclick={() => apply(c)}>{c.label}</button>
			{/each}
		</div>
	{/if}
</div>

<style>
	.field {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		min-width: 0;
	}
	.label {
		font: var(--kb-type-label);
	}
	.box {
		display: flex;
		align-items: flex-start;
		gap: var(--kb-space-2);
		min-height: var(--kb-size-control);
		padding: 0 var(--kb-space-3);
		border: 1px solid var(--kb-color-border-strong);
		border-radius: var(--kb-radius-sm);
		background: var(--kb-color-surface);
		transition:
			border-color var(--kb-duration-fast) var(--kb-ease-standard),
			box-shadow var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.box:focus-within {
		border-color: var(--kb-color-focus);
		box-shadow: 0 0 0 1px var(--kb-color-focus);
	}
	.invalid .box {
		border-color: var(--kb-color-danger);
	}
	.icon {
		display: flex;
		align-items: center;
		height: calc(var(--kb-size-control) - 2px);
		color: var(--kb-color-text-muted);
	}
	input,
	textarea {
		flex: 1;
		min-width: 0;
		min-height: calc(var(--kb-size-control) - 2px);
		padding: 0;
		border: 0;
		outline: 0;
		background: transparent;
		color: var(--kb-color-text);
		font: 0.9375rem/1.5 var(--kb-family-mono);
	}
	textarea {
		padding: var(--kb-space-2) 0;
		resize: vertical;
	}
	input::placeholder,
	textarea::placeholder {
		color: var(--kb-color-text-muted);
		opacity: 1;
		font-family: var(--kb-family-sans);
	}
	.status:empty {
		display: none;
	}
	.msg {
		display: flex;
		gap: var(--kb-space-1);
		align-items: flex-start;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.msg :global(svg) {
		flex: none;
		margin-top: 0.15em;
	}
	.error {
		color: var(--kb-color-danger);
	}
	code {
		font-family: var(--kb-family-mono);
		margin-inline-end: var(--kb-space-1);
	}
	.ok {
		color: var(--kb-color-text-muted);
	}
	.bad {
		text-decoration: underline wavy var(--kb-color-danger);
		text-underline-offset: 0.2em;
	}
	.suggest {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1);
	}
	.suggest button {
		min-height: 2rem;
		padding: 0 var(--kb-space-3);
		border: 1px solid var(--kb-color-border-strong);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface);
		color: var(--kb-color-text);
		font: 0.875rem/1 var(--kb-family-mono);
		cursor: pointer;
		position: relative;
	}
	.suggest button::after {
		content: '';
		position: absolute;
		inset: -6px 0;
	}
	.suggest button:hover {
		background: var(--kb-color-surface-hover);
	}
</style>
