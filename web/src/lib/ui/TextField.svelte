<script lang="ts">
	import type { HTMLInputAttributes } from 'svelte/elements';
	import Icon from './Icon.svelte';
	import type { IconName } from './icons';
	import { ut } from './messages';

	interface Props extends Omit<HTMLInputAttributes, 'value'> {
		/** Visible label. For a search box with no visible label, set hideLabel (it stays the accessible name). */
		label: string;
		hideLabel?: boolean;
		value?: string;
		hint?: string;
		error?: string;
		icon?: IconName;
		/** Shows a clear button while there is text (search boxes). */
		clearable?: boolean;
	}
	let {
		label,
		hideLabel = false,
		value = $bindable(''),
		hint,
		error,
		icon,
		clearable = false,
		id,
		type = 'text',
		disabled,
		...rest
	}: Props = $props();

	const uid = $props.id();
	const inputId = $derived(id ?? `tf-${uid}`);
	const describedBy = $derived([error ? `${inputId}-error` : '', hint ? `${inputId}-hint` : ''].filter(Boolean).join(' ') || undefined);
	let input: HTMLInputElement | undefined = $state();
</script>

<div class="field" class:invalid={!!error} class:disabled>
	<label class="label" class:sr={hideLabel} for={inputId}>{label}</label>
	<div class="box">
		{#if icon}<span class="icon"><Icon name={icon} size={20} /></span>{/if}
		<input
			bind:this={input}
			id={inputId}
			{type}
			bind:value
			{disabled}
			aria-invalid={error ? 'true' : undefined}
			aria-describedby={describedBy}
			{...rest}
		/>
		{#if clearable && value}
			<button
				type="button"
				class="clear"
				aria-label={ut('ui.clear')}
				onclick={() => {
					value = '';
					input?.focus();
				}}><Icon name="close" size={18} /></button
			>
		{/if}
	</div>
	{#if error}
		<p class="msg error" id="{inputId}-error"><Icon name="alert" size={16} />{error}</p>
	{/if}
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
	.sr {
		position: absolute;
		width: 1px;
		height: 1px;
		overflow: hidden;
		clip-path: inset(50%);
		white-space: nowrap;
	}
	.box {
		display: flex;
		align-items: center;
		gap: var(--kb-space-2);
		min-height: var(--kb-size-control);
		padding: 0 var(--kb-space-1) 0 var(--kb-space-3);
		border: 1px solid var(--kb-color-border-strong);
		border-radius: var(--kb-radius-sm);
		background: var(--kb-color-surface);
		transition:
			border-color var(--kb-duration-fast) var(--kb-ease-standard),
			box-shadow var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.box:hover {
		border-color: var(--kb-color-text-muted);
	}
	.box:focus-within {
		border-color: var(--kb-color-focus);
		box-shadow: 0 0 0 1px var(--kb-color-focus);
	}
	.invalid .box {
		border-color: var(--kb-color-danger);
	}
	.invalid .box:focus-within {
		box-shadow: 0 0 0 1px var(--kb-color-danger);
	}
	.disabled .box {
		background: var(--kb-color-surface-sunken);
		border-color: var(--kb-color-border);
	}
	input {
		flex: 1;
		min-width: 0;
		height: calc(var(--kb-size-control) - 2px);
		padding: 0 var(--kb-space-2) 0 0;
		border: 0;
		outline: 0;
		background: transparent;
		color: var(--kb-color-text);
		font: var(--kb-type-body);
	}
	input::placeholder {
		color: var(--kb-color-text-muted);
		opacity: 1;
	}
	input:disabled {
		color: var(--kb-color-text-disabled);
	}
	.icon {
		display: flex;
		color: var(--kb-color-text-muted);
	}
	.clear {
		display: grid;
		place-items: center;
		width: 2.25rem;
		height: 2.25rem;
		border: 0;
		border-radius: var(--kb-radius-full);
		background: transparent;
		color: var(--kb-color-text-muted);
		cursor: pointer;
	}
	.clear:hover {
		background: var(--kb-color-surface-hover);
	}
	.clear:focus-visible {
		outline: 2px solid var(--kb-color-focus);
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
	}
	.error {
		color: var(--kb-color-danger);
	}
</style>
