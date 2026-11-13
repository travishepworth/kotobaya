<script lang="ts">
	// A file picker that is also a drop zone: a dashed well with an icon, a title and a hint. The native input stays
	// the control (keyboard, screen readers); dropping files sets it too.
	import Icon from './Icon.svelte';
	import type { IconName } from './icons';

	interface Props {
		title: string;
		hint?: string;
		accept?: string;
		multiple?: boolean;
		icon?: IconName;
		/** Shown instead of the hint once something is chosen. */
		chosen?: string;
		onfiles: (files: FileList | null) => void;
	}
	let { title, hint, accept, multiple = false, icon = 'import', chosen, onfiles }: Props = $props();
	let over = $state(false);
	const uid = $props.id();
</script>

<label
	class="drop"
	class:over
	class:chosen={!!chosen}
	for="fd-{uid}"
	ondragover={(e) => {
		e.preventDefault();
		over = true;
	}}
	ondragleave={() => (over = false)}
	ondrop={(e) => {
		e.preventDefault();
		over = false;
		onfiles(e.dataTransfer?.files ?? null);
	}}
>
	<span class="disc"><Icon name={chosen ? 'success' : icon} size={24} /></span>
	<span class="title">{title}</span>
	{#if chosen}<span class="hint strong">{chosen}</span>{:else if hint}<span class="hint">{hint}</span>{/if}
	<input id="fd-{uid}" type="file" {accept} {multiple} onchange={(e) => onfiles(e.currentTarget.files)} />
</label>

<style>
	.drop {
		position: relative;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--kb-space-2);
		padding: var(--kb-space-8) var(--kb-space-4);
		border: 2px dashed var(--kb-color-border-strong);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
		text-align: center;
		cursor: pointer;
		transition:
			background-color var(--kb-duration-fast) var(--kb-ease-standard),
			border-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.drop:hover,
	.over {
		border-color: var(--kb-color-primary);
		background: var(--kb-color-surface-selected);
	}
	.chosen {
		border-style: solid;
		border-color: var(--kb-color-primary);
	}
	.drop:has(input:focus-visible) {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
	.disc {
		display: grid;
		place-items: center;
		width: 3rem;
		height: 3rem;
		border-radius: 50%;
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
	}
	.title {
		font: var(--kb-type-title-sm);
	}
	.hint {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		overflow-wrap: anywhere;
	}
	.hint.strong {
		color: var(--kb-color-text);
	}
	input {
		position: absolute;
		width: 1px;
		height: 1px;
		opacity: 0;
	}
</style>
