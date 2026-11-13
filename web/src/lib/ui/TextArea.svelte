<script lang="ts">
	// Multi-line text input with the TextField anatomy (visible label, hint, error under the field).
	import type { HTMLTextareaAttributes } from 'svelte/elements';
	import Icon from './Icon.svelte';

	interface Props extends Omit<HTMLTextareaAttributes, 'value'> {
		label: string;
		hideLabel?: boolean;
		value?: string;
		hint?: string;
		error?: string;
		/** Monospace (templates, CSS, rules). */
		code?: boolean;
	}
	let { label, hideLabel = false, value = $bindable(''), hint, error, code = false, id, rows = 3, ...rest }: Props = $props();
	const uid = $props.id();
	const inputId = $derived(id ?? `ta-${uid}`);
	const describedBy = $derived(
		[error ? `${inputId}-error` : '', hint ? `${inputId}-hint` : ''].filter(Boolean).join(' ') || undefined
	);
</script>

<div class="field" class:invalid={!!error}>
	<label class="label" class:kb-sr={hideLabel} for={inputId}>{label}</label>
	<textarea
		id={inputId}
		class:code
		{rows}
		bind:value
		aria-invalid={error ? 'true' : undefined}
		aria-describedby={describedBy}
		{...rest}
	></textarea>
	{#if error}<p class="msg error" id="{inputId}-error"><Icon name="alert" size={16} />{error}</p>{/if}
	{#if hint}<p class="msg" id="{inputId}-hint">{hint}</p>{/if}
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
		color: var(--kb-color-text);
	}
	textarea {
		width: 100%;
		min-height: var(--kb-size-control);
		padding: var(--kb-space-2) var(--kb-space-3);
		border: 1px solid var(--kb-color-border-strong);
		border-radius: var(--kb-radius-sm);
		background: var(--kb-color-surface);
		color: var(--kb-color-text);
		font: var(--kb-type-body);
		resize: vertical;
		transition:
			border-color var(--kb-duration-fast) var(--kb-ease-standard),
			box-shadow var(--kb-duration-fast) var(--kb-ease-standard);
	}
	textarea:hover {
		border-color: var(--kb-color-text-muted);
	}
	textarea:focus {
		outline: 0;
		border-color: var(--kb-color-focus);
		box-shadow: 0 0 0 1px var(--kb-color-focus);
	}
	.code {
		font: 0.875rem/1.6 var(--kb-family-mono);
		tab-size: 2;
	}
	.invalid textarea {
		border-color: var(--kb-color-danger);
	}
	.msg {
		display: flex;
		gap: var(--kb-space-1);
		align-items: flex-start;
		margin: 0;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.msg :global(svg) {
		margin-top: 0.15em;
		flex: none;
	}
	.error {
		color: var(--kb-color-danger);
	}
</style>
