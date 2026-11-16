<script lang="ts">
	// The review screen with no card (ADR-037 C4): rendered from `loop.phase` only.
	// goalReached: the new bar full, Keep going. doneForToday: reviews and new against their limits, the backlog
	// left, Keep going. waiting: the countdown to the next learning card. done: nothing left today.
	import Button from '../../lib/ui/Button.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import Icon from '../../lib/ui/Icon.svelte';
	import ProgressBar from '../../lib/ui/ProgressBar.svelte';
	import { tr } from '../../lib/review/messages';
	import { countdown, type LoopView } from '../../lib/state/review.svelte';
	import { rv } from './messages';

	interface Props {
		phase: 'goalReached' | 'doneForToday' | 'waiting' | 'done';
		view: LoopView | null;
		busy: boolean;
		canUndo: boolean;
		/** The last check failed (an inline notice; the screen stays). */
		checkFailed: boolean;
		onkeepgoing: () => void;
		onundo: () => void;
		onreload: () => void;
	}
	let { phase, view, busy, canUndo, checkFailed, onkeepgoing, onundo, onreload }: Props = $props();

	const loop = $derived(view && !view.legacy ? view.loop : null);

	// The countdown ticks once a second; the refetch itself is one timer in the state (C4).
	let now = $state(Date.now());
	$effect(() => {
		if (phase !== 'waiting') return;
		now = Date.now();
		const t = setInterval(() => (now = Date.now()), 1000);
		return () => clearInterval(t);
	});
	const time = $derived(countdown(view?.loop.laterToday.nextDueAt ?? null, now));
</script>

<div class="finished" aria-live="polite" data-phase={phase}>
	{#if phase === 'waiting'}
		<div class="wait">
			<Icon name="clock" size={40} />
			<h2>{tr('loop.waiting.next')}</h2>
			<p class="countdown kb-num" aria-label={tr('loop.waiting.title', { time })}>{time}</p>
			{#if checkFailed}<p class="inline-error" role="status">{rv('rv.checkFailed')}</p>{/if}
			<p class="muted">{tr('loop.waiting.body')}</p>
			<div class="actions">
				<Button variant="outline" icon="sync" loading={busy} onclick={onreload}>{rv('rv.done.check')}</Button>
				{#if canUndo}<Button variant="quiet" icon="undo" onclick={onundo}>{rv('rv.undo')}</Button>{/if}
				<Button variant="quiet" href="/">{rv('rv.done.today')}</Button>
			</div>
		</div>
	{:else if phase === 'goalReached' || phase === 'doneForToday'}
		<EmptyState
			size="page"
			art="enso"
			title={phase === 'goalReached' ? tr('loop.goalReached.title') : tr('loop.doneForToday.title')}
			body={phase === 'goalReached' ? tr('loop.goalReached.body') : tr('loop.doneForToday.body')}
		>
			{#snippet action()}
				<div class="stack">
					{#if loop}
						<dl class="figures">
							{#if phase === 'doneForToday'}
								<div data-testid="end-reviews">
									<dt>{loop.reviews.limit === null ? tr('loop.reviewsToday', { today: loop.reviews.today }) : tr('loop.reviewsOf', { today: loop.reviews.today, limit: loop.reviews.limit })}</dt>
									<dd><ProgressBar value={Math.min(loop.reviews.today, loop.reviews.limit ?? loop.reviews.today)} max={loop.reviews.limit ?? Math.max(loop.reviews.today, 1)} label={tr('loop.reviewsToday', { today: loop.reviews.today })} /></dd>
								</div>
							{/if}
							<div data-testid="end-new">
								<dt>{tr('loop.newOfLong', { today: loop._new.today, goal: loop._new.goal })}</dt>
								<dd><ProgressBar value={Math.min(loop._new.today, loop._new.goal)} max={loop._new.goal} label={tr('loop.newBar')} /></dd>
							</div>
						</dl>
						{#if loop.backlog.beyondLimit > 0}
							<p class="muted" data-testid="end-backlog">{tr('loop.backlogMore', { n: loop.backlog.reviews.toLocaleString() })}</p>
						{/if}
					{/if}
					{#if checkFailed}<p class="inline-error" role="status">{rv('rv.checkFailed')}</p>{/if}
					<div class="actions">
						<Button variant="primary" loading={busy} onclick={onkeepgoing}>{tr('loop.keepGoing')}</Button>
						<Button variant="outline" href="/">{rv('rv.done.today')}</Button>
						{#if canUndo}<Button variant="quiet" icon="undo" onclick={onundo}>{rv('rv.undo')}</Button>{/if}
					</div>
				</div>
			{/snippet}
		</EmptyState>
	{:else}
		<EmptyState
			size="page"
			art="enso"
			title={tr('loop.empty.title')}
			body={view?.loop.keepGoing ? tr('loop.empty.keepGoing') : tr('loop.empty.body')}
		>
			{#snippet action()}
				<div class="actions">
					<Button variant="primary" href="/">{rv('rv.done.today')}</Button>
					<Button variant="outline" icon="sync" loading={busy} onclick={onreload}>{rv('rv.done.check')}</Button>
					{#if canUndo}<Button variant="quiet" icon="undo" onclick={onundo}>{rv('rv.undo')}</Button>{/if}
					<a class="quiet-link" href="/struggle/leeches">{rv('rv.done.leeches')}</a>
				</div>
			{/snippet}
		</EmptyState>
	{/if}
</div>

<style>
	.finished {
		flex: 1;
		display: grid;
		place-items: center;
		padding: var(--kb-space-8) 0;
	}
	.finished :global(.enso) {
		color: var(--kb-color-brand);
	}
	.wait {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--kb-space-3);
		text-align: center;
		color: var(--kb-color-text);
	}
	.wait :global(svg) {
		color: var(--kb-color-state-learning);
	}
	.wait h2 {
		font: var(--kb-type-title);
		margin: 0;
	}
	.countdown {
		font-size: 3rem;
		line-height: 1;
		margin: 0;
		font-variant-numeric: tabular-nums;
	}
	.stack {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--kb-space-4);
		width: 100%;
	}
	.figures {
		display: grid;
		gap: var(--kb-space-3);
		width: min(18rem, 100%);
		margin: 0;
	}
	.figures div {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
	}
	dt {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text);
		font-variant-numeric: tabular-nums;
	}
	dd {
		margin: 0;
	}
	.muted {
		margin: 0;
		max-width: 28rem;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		text-align: center;
	}
	.inline-error {
		margin: 0;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-danger, var(--kb-color-text-muted));
	}
	.actions {
		display: flex;
		flex-wrap: wrap;
		justify-content: center;
		align-items: center;
		gap: var(--kb-space-2);
	}
	.quiet-link {
		display: inline-flex;
		align-items: center;
		min-height: var(--kb-size-touch-min);
		padding: 0 var(--kb-space-3);
		font: var(--kb-type-label);
	}
</style>
