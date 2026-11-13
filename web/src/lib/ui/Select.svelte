<script lang="ts" generics="T extends string | number">
	// A native select in the TextField anatomy (44 px, visible label, chevron).
	import Icon from './Icon.svelte';

	interface Option {
		value: T;
		label: string;
		disabled?: boolean;
	}
	interface Props {
		label: string;
		hideLabel?: boolean;
		options: Option[];
		value: T;
		hint?: string;
		disabled?: boolean;
		onchange?: (value: T) => void;
	}
	let { label, hideLabel = false, options, value = $bindable(), hint, disabled = false, onchange }: Props = $props();
	const uid = $props.id();
</script>

<div class="field">
	<label class="label" class:kb-sr={hideLabel} for="sel-{uid}">{label}</label>
	<div class="box">
		<select
			id="sel-{uid}"
			bind:value
			{disabled}
			aria-describedby={hint ? `sel-${uid}-hint` : undefined}
			onchange={() => onchange?.(value)}
		>
			{#each options as o (o.value)}
				<option value={o.value} disabled={o.disabled}>{o.label}</option>
			{/each}
		</select>
		<span class="chev"><Icon name="chevronDown" size={18} /></span>
	</div>
	{#if hint}<p class="hint" id="sel-{uid}-hint">{hint}</p>{/if}
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
		position: relative;
		display: flex;
	}
	select {
		appearance: none;
		width: 100%;
		min-height: var(--kb-size-control);
		padding: 0 2.5rem 0 var(--kb-space-3);
		border: 1px solid var(--kb-color-border-strong);
		border-radius: var(--kb-radius-sm);
		background: var(--kb-color-surface);
		color: var(--kb-color-text);
		font: var(--kb-type-body);
		cursor: pointer;
		text-overflow: ellipsis;
	}
	select:hover {
		border-color: var(--kb-color-text-muted);
	}
	select:focus-visible {
		outline: 0;
		border-color: var(--kb-color-focus);
		box-shadow: 0 0 0 1px var(--kb-color-focus);
	}
	select:disabled {
		background: var(--kb-color-surface-sunken);
		color: var(--kb-color-text-disabled);
		cursor: default;
	}
	.chev {
		position: absolute;
		right: var(--kb-space-3);
		top: 50%;
		transform: translateY(-50%);
		display: flex;
		color: var(--kb-color-text-muted);
		pointer-events: none;
	}
	.hint {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
</style>
