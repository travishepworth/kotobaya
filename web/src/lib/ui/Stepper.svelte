<script lang="ts">
	// A short horizontal stepper (Upload → Import → Review → Done): numbered discs, the current step emphasised,
	// finished ones ticked. Labels wrap under the discs when narrow.
	import Icon from './Icon.svelte';

	interface Props {
		label: string;
		steps: string[];
		current: number;
	}
	let { label, steps, current }: Props = $props();
</script>

<ol class="stepper" aria-label={label}>
	{#each steps as s, i (s)}
		<li class:done={i < current} class:now={i === current} aria-current={i === current ? 'step' : undefined}>
			<span class="disc">{#if i < current}<Icon name="check" size={14} />{:else}{i + 1}{/if}</span>
			<span class="text">{s}</span>
		</li>
	{/each}
</ol>

<style>
	.stepper {
		display: flex;
		gap: var(--kb-space-2);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	li {
		flex: 1;
		display: flex;
		align-items: center;
		gap: var(--kb-space-2);
		min-width: 0;
		padding-top: var(--kb-space-2);
		border-top: 3px solid var(--kb-color-border);
		color: var(--kb-color-text-muted);
		font: var(--kb-type-label);
	}
	li.done {
		border-top-color: var(--kb-color-primary);
	}
	li.now {
		border-top-color: var(--kb-color-primary);
		color: var(--kb-color-text);
	}
	.disc {
		display: grid;
		place-items: center;
		flex: none;
		width: 1.5rem;
		height: 1.5rem;
		border-radius: 50%;
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-caption);
		font-weight: 600;
	}
	.done .disc,
	.now .disc {
		background: var(--kb-color-primary);
		color: var(--kb-color-on-primary);
	}
	.text {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	@media (max-width: 599.98px) {
		li:not(.now) .text {
			position: absolute;
			width: 1px;
			height: 1px;
			overflow: hidden;
			clip-path: inset(50%);
		}
	}
</style>
