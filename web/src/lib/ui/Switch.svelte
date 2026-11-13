<script lang="ts">
	// An on/off setting: a labelled switch (role="switch"); the whole row is the hit area.
	interface Props {
		label: string;
		description?: string;
		checked?: boolean;
		disabled?: boolean;
		onchange?: (checked: boolean) => void;
	}
	let { label, description, checked = $bindable(false), disabled = false, onchange }: Props = $props();
	const uid = $props.id();
</script>

<label class="row" class:disabled>
	<span class="text">
		<span class="label" id="sw-{uid}">{label}</span>
		{#if description}<span class="desc">{description}</span>{/if}
	</span>
	<input
		type="checkbox"
		role="switch"
		bind:checked
		{disabled}
		aria-labelledby="sw-{uid}"
		onchange={() => onchange?.(checked)}
	/>
	<span class="track" aria-hidden="true"><span class="thumb"></span></span>
</label>

<style>
	.row {
		position: relative;
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2) var(--kb-space-4);
		min-height: var(--kb-size-touch-min);
		cursor: pointer;
	}
	.disabled {
		cursor: default;
		color: var(--kb-color-text-disabled);
	}
	.text {
		display: flex;
		flex-direction: column;
		flex: 1 1 10rem;
		min-width: 0;
	}
	.label {
		font: var(--kb-type-body);
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.desc {
		min-width: 0;
		overflow-wrap: anywhere;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	input {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		margin: 0;
		opacity: 0;
		cursor: inherit;
	}
	.track {
		position: relative;
		flex: none;
		width: 2.75rem;
		height: 1.625rem;
		border: 1px solid var(--kb-color-border-strong);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		transition: background-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.thumb {
		position: absolute;
		top: 50%;
		left: 3px;
		width: 1.125rem;
		height: 1.125rem;
		border-radius: 50%;
		background: var(--kb-color-border-strong);
		transform: translateY(-50%);
		transition:
			transform var(--kb-duration-fast) var(--kb-ease-standard),
			background-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	input:checked + .track {
		background: var(--kb-color-primary);
		border-color: var(--kb-color-primary);
	}
	input:checked + .track .thumb {
		background: var(--kb-color-on-primary);
		transform: translate(1.125rem, -50%);
	}
	input:focus-visible + .track {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
</style>
