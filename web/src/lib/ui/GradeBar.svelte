<script lang="ts">
	import Kbd from './Kbd.svelte';
	import { ut } from './messages';
	import { GRADES, type Grade, type GradeOption } from './kinds';

	interface Props {
		/** Interval previews per grade ("<1m", "2d"); from the state layer (core previewIntervals). */
		intervals?: Record<Grade, string>;
		/** Explicit buttons (5-button grading, drills). Overrides `intervals`. */
		options?: GradeOption[];
		ongrade: (g: Grade, index: number) => void;
		disabled?: boolean;
		/** Shows 1-4 key hints (desktop; hidden on touch-only devices regardless). */
		keys?: boolean;
	}
	let { intervals, options, ongrade, disabled = false, keys = true }: Props = $props();

	const list = $derived<GradeOption[]>(
		options ?? GRADES.map((g) => ({ id: g, label: ut(`ui.grade.${g}`), tone: g, interval: intervals?.[g] }))
	);
	// The default answer (Good / Okay) carries the full outline.
	const defaultIndex = $derived(list.findIndex((o) => o.tone === 'good'));
</script>

<div class="bar">
<div class="grades" class:five={list.length === 5} role="group" aria-label={ut('ui.grades')}>
	{#each list as o, i (o.id)}
		<button
			type="button"
			class="grade {o.tone}"
			class:default={i === defaultIndex}
			{disabled}
			onclick={() => ongrade(o.tone, i)}
			aria-keyshortcuts={String(i + 1)}
			aria-label={o.interval ? `${o.label}, ${o.interval}` : o.label}
		>
			<span class="name">{o.label}</span>
			{#if o.interval}<span class="interval">{o.interval}</span>{/if}
			{#if keys}<span class="key"><Kbd keys={String(i + 1)} /></span>{/if}
		</button>
	{/each}
</div>
</div>

<style>
	.bar {
		container-type: inline-size;
		width: 100%;
	}
	.grades {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: var(--kb-space-2);
		width: 100%;
	}
	.grade {
		--fg: var(--kb-color-grade-good);
		--bg: var(--kb-color-grade-good-container);
		position: relative;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 2px;
		min-height: var(--kb-size-grade-button);
		padding: var(--kb-space-2) var(--kb-space-1);
		border: 1px solid transparent;
		border-top: 3px solid var(--fg);
		border-radius: var(--kb-radius-md);
		background: var(--bg);
		color: var(--kb-color-text);
		cursor: pointer;
		transition:
			transform var(--kb-duration-instant) var(--kb-ease-standard),
			background-color var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.again {
		--fg: var(--kb-color-grade-again);
		--bg: var(--kb-color-grade-again-container);
	}
	.hard {
		--fg: var(--kb-color-grade-hard);
		--bg: var(--kb-color-grade-hard-container);
	}
	.easy {
		--fg: var(--kb-color-grade-easy);
		--bg: var(--kb-color-grade-easy-container);
	}
	/* Good is the default answer: it carries a full outline. */
	.default {
		border-color: var(--fg);
	}
	.five {
		grid-template-columns: repeat(5, minmax(0, 1fr));
	}
	.grade:hover:not(:disabled) {
		background: color-mix(in oklab, var(--bg), var(--fg) 12%);
	}
	.grade:active:not(:disabled) {
		transform: scale(0.97);
	}
	.grade:focus-visible {
		outline: 2px solid var(--kb-color-focus);
		outline-offset: 2px;
	}
	.grade:disabled {
		opacity: 0.5;
		cursor: default;
	}
	.name {
		font: var(--kb-type-label);
		font-weight: 600;
		color: var(--fg);
	}
	.interval {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		font-variant-numeric: tabular-nums;
	}
	.key {
		position: absolute;
		top: var(--kb-space-1);
		inset-inline-end: var(--kb-space-1);
		display: none;
	}
	/* Narrow bar (phones at large text sizes; rem follows the font size): 2 × 2, Again/Hard above Good/Easy. */
	@container (max-width: 24rem) {
		.grades {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}
	/* Five buttons when narrow: the two "fail" answers on top, the other three below. */
	@container (max-width: 34rem) {
		.five {
			grid-template-columns: repeat(6, minmax(0, 1fr));
		}
		.five .grade {
			grid-column: span 2;
		}
		.five .grade:nth-child(-n + 2) {
			grid-column: span 3;
		}
	}
	/* Key hints: keyboard devices only, and only when each button has room for them. */
	@media (hover: hover) and (pointer: fine) {
		@container (min-width: 30rem) {
			.key {
				display: block;
			}
		}
	}
</style>
