<script lang="ts">
	// ADR-037 C3: the lines under the headline, from `loop` only: the later-today hint, the new-card bar
	// (today/goal; past the goal after keep going the bar is full and the number is shown) and the backlog line
	// (only when reviews are held back by the limit). Hidden in legacy mode (no `loop` from the server).
	import ProgressBar from '../ui/ProgressBar.svelte';
	import { minutesUntil, type LoopView } from '../state/today.svelte';
	import { quotaLine, tr } from './messages';

	interface Props {
		view: LoopView;
		now: number;
		/** Show the later-today hint (the review end screens show their own countdown instead). */
		hint?: boolean;
	}
	let { view, now, hint = true }: Props = $props();

	const loop = $derived(view.loop);
	// Done for today / goal reached: the quota line replaces the new-card bar (ADR-037 C3, quota form).
	const endPhase = $derived(view.phase === 'goal_reached' || view.phase === 'done_for_today');
	const quota = $derived(quotaLine(loop, view.phase === 'goal_reached'));
	const later = $derived.by(() => {
		const at = loop.laterToday.nextDueAt;
		if (!hint || view.legacy || loop.laterToday.count === 0 || !at) return null;
		const min = minutesUntil(at, now);
		return min <= 60
			? tr('loop.later', { n: loop.laterToday.count, min })
			: tr('loop.laterAt', { n: loop.laterToday.count, time: at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) });
	});
	const goalMet = $derived(loop._new.today >= loop._new.goal);
</script>

{#if !view.legacy}
	<div class="meta">
		{#if later}<p class="later" data-testid="loop-later">{later}</p>{/if}
		{#if endPhase}
			<p class="quota kb-num" data-testid="loop-quota">{quota}</p>
		{:else}
			<div class="new" data-testid="loop-new">
				<span class="label">{goalMet ? tr('loop.newToday', { n: loop._new.today }) : tr('loop.newBar')}</span>
				<div class="bar">
					<ProgressBar value={Math.min(loop._new.today, loop._new.goal)} max={loop._new.goal} label={tr('loop.newBar')} />
				</div>
				{#if !goalMet}<span class="num kb-num">{tr('loop.newOf', { today: loop._new.today, goal: loop._new.goal })}</span>{/if}
			</div>
		{/if}
		{#if loop.backlog.beyondLimit > 0}
			<p class="backlog" data-testid="loop-backlog">
				{tr('loop.backlog', { n: loop.backlog.reviews.toLocaleString(), limit: loop.reviews.limit ?? '–' })}
			</p>
		{/if}
	</div>
{:else}
	<p class="later">{tr('loop.legacy')}</p>
{/if}

<style>
	.meta {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		min-width: 0;
		width: 100%;
	}
	.later,
	.quota,
	.backlog {
		margin: 0;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		font-variant-numeric: tabular-nums;
	}
	.new {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		min-width: 0;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		font-variant-numeric: tabular-nums;
	}
	.label {
		flex: none;
		white-space: nowrap;
	}
	.bar {
		flex: 1 1 4rem;
		min-width: 3rem;
		max-width: 12rem;
	}
	.num {
		flex: none;
	}
</style>
