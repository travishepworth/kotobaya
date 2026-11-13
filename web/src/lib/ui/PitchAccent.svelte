<script lang="ts">
	import { ut } from './messages';
	import { pitchShape } from './kinds';

	interface Props {
		/** The reading split into morae (from the state layer / core; きょ counts as one mora). */
		morae: string[];
		/** Downstep position: 0 heiban, 1 atamadaka, n = the drop comes after mora n. */
		downstep: number;
		/** Shows the pattern name and number next to the contour. */
		showLabel?: boolean;
	}
	let { morae, downstep, showLabel = true }: Props = $props();
	const shape = $derived(pitchShape(morae.length, downstep));
	const name = $derived(ut(`ui.pitch.${shape.pattern}`));
</script>

<span class="pitch {shape.pattern}" aria-label={ut('ui.pitch.label', { pattern: name, n: downstep })} role="img">
	<span class="morae" lang="ja" aria-hidden="true">
		{#each morae as m, i (i)}
			<span class="mora" class:high={shape.high[i]} class:drop={downstep === i + 1}>{m}</span>
		{/each}
	</span>
	{#if showLabel}
		<span class="tag" aria-hidden="true"><span class="n">{downstep}</span>{name}</span>
	{/if}
</span>

<style>
	.pitch {
		--pc: var(--kb-color-pitch-heiban);
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-2);
		flex-wrap: wrap;
	}
	.atamadaka {
		--pc: var(--kb-color-pitch-atamadaka);
	}
	.nakadaka {
		--pc: var(--kb-color-pitch-nakadaka);
	}
	.odaka {
		--pc: var(--kb-color-pitch-odaka);
	}
	.morae {
		display: inline-flex;
		font: var(--kb-type-reading);
		letter-spacing: var(--kb-type-reading-tracking);
	}
	.mora {
		padding-top: 0.15em;
		border-top: 2px solid transparent;
	}
	.high {
		border-top-color: var(--pc);
	}
	.drop {
		border-right: 2px solid var(--pc);
	}
	.tag {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
		font: var(--kb-type-caption);
		font-weight: 600;
		color: var(--pc);
	}
	.n {
		display: inline-grid;
		place-items: center;
		min-width: 1.25rem;
		height: 1.25rem;
		padding: 0 0.25rem;
		border: 1.5px solid currentColor;
		border-radius: var(--kb-radius-xs);
		font-variant-numeric: tabular-nums;
	}
</style>
