<script lang="ts">
	// Today hero (ADR-037): rendered from `loop` only. `reviewing`: the ready-now number (always `ready.total`,
	// C3) and Start; `goal_reached` / `done_for_today`: Keep going; `waiting`: a countdown to the next learning
	// card; `empty`: all done. Under it the later-today hint, the new-card bar and the backlog line.
	import Button from '../ui/Button.svelte';
	import Icon from '../ui/Icon.svelte';
	import { countdown, keepGoingAvailable, type LoopView } from '../state/today.svelte';
	import { presentedParts } from './loop';
	import LoopMeta from './LoopMeta.svelte';
	import { tr } from './messages';

	interface Props {
		view: LoopView;
		/** How long the ready cards take ("About 4 min"), shown under Start when cards are ready. */
		eta?: string | null;
		onkeepgoing: () => void;
	}
	let { view, eta = null, onkeepgoing }: Props = $props();

	// The hint and the countdown tick: every second while waiting, else twice a minute.
	let now = $state(Date.now());
	$effect(() => {
		const ms = view.phase === 'waiting' ? 1000 : 30_000;
		now = Date.now();
		const t = setInterval(() => (now = Date.now()), ms);
		return () => clearInterval(t);
	});

	const loop = $derived(view.loop);
	const ready = $derived(loop.ready.total);
	// ADR-049 C3: the same four numbers as the review header; `later` is in the hint line below while reviewing.
	const parts = $derived(presentedParts(view));
</script>

{#if view.phase === 'reviewing'}
	<div class="due" data-phase="reviewing">
		<p class="overline" id="due-title">{tr('loop.readyNow')}</p>
		<p class="figure">
			<span class="n kb-num" data-testid="loop-ready">{ready.toLocaleString()}</span>
			<span class="unit">{ready === 1 ? tr('loop.card') : tr('loop.cards')}</span>
		</p>
		{#if ready > 0 || parts.new > 0}
			<ul class="breakdown" data-testid="loop-parts">
				<li><i class="learning"></i>{tr('loop.ready.learning', { n: parts.learning })}</li>
				<li><i class="review"></i>{tr('loop.ready.review', { n: parts.review.toLocaleString() })}</li>
				{#if parts.new > 0}<li><i class="new"></i>{tr('loop.part.new', { n: parts.new.toLocaleString() })}</li>{/if}
			</ul>
		{/if}
		<LoopMeta {view} {now} />
	</div>
	<div class="go">
		<Button variant="primary" size="lg" href="/review" icon="review" full>{tr('loop.start')}</Button>
		{#if ready > 0 && eta}
			<p class="eta"><Icon name="clock" size={16} />{eta}</p>
		{/if}
	</div>
{:else}
	<div class="done" data-phase={view.phase}>
		<svg class="enso" viewBox="0 0 120 120" aria-hidden="true">
			<path
				d="M93 28c-9-9-21-13-34-12C33 18 15 38 16 62c1 24 21 43 46 42 24-1 42-20 42-43 0-9-3-17-7-24"
				fill="none"
				stroke="currentColor"
				stroke-width="8"
				stroke-linecap="round"
			/>
		</svg>
		<div class="text">
			{#if view.phase === 'goal_reached'}
				<h2 id="due-title">{tr('loop.goalReached.title')}</h2>
			{:else if view.phase === 'done_for_today'}
				<h2 id="due-title">{tr('loop.doneForToday.title')}</h2>
			{:else if view.phase === 'waiting'}
				<h2 id="due-title">{tr('loop.waiting.homeTitle')}</h2>
				<p class="countdown kb-num" data-testid="loop-countdown">
					{tr('loop.waiting.title', { time: countdown(loop.laterToday.nextDueAt, now) })}
				</p>
				<ul class="breakdown" data-testid="loop-parts">
					{#if parts.later > 0}<li><i class="learning"></i>{tr('loop.part.later', { n: parts.later })}</li>{/if}
					{#if parts.review > 0}<li><i class="review"></i>{tr('loop.part.review', { n: parts.review.toLocaleString() })}</li>{/if}
					{#if parts.new > 0}<li><i class="new"></i>{tr('loop.part.new', { n: parts.new.toLocaleString() })}</li>{/if}
				</ul>
			{:else}
				<h2 id="due-title">{tr('loop.empty.title')}</h2>
				<p class="muted">{view.loop.keepGoing ? tr('loop.empty.keepGoing') : tr('loop.empty.body')}</p>
			{/if}
			<LoopMeta {view} {now} hint={view.phase !== 'waiting'} />
		</div>
	</div>
	{#if keepGoingAvailable(view)}
		<div class="go">
			<Button variant="primary" size="lg" icon="review" full onclick={onkeepgoing}>{tr('loop.keepGoing')}</Button>
		</div>
	{/if}
{/if}

<style>
	.due {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		min-width: 0;
		flex: 1 1 14rem;
	}
	.overline {
		margin: 0;
		font: var(--kb-type-overline);
		letter-spacing: var(--kb-type-overline-tracking);
		text-transform: uppercase;
		color: var(--kb-color-text-muted);
	}
	.figure {
		display: flex;
		align-items: baseline;
		gap: var(--kb-space-2);
		margin: 0;
	}
	.n {
		font: var(--kb-type-display);
		font-size: clamp(3rem, 2.4rem + 2.6vw, 4.5rem);
		line-height: 1;
		letter-spacing: -0.02em;
	}
	.unit {
		font: var(--kb-type-title);
		color: var(--kb-color-text-muted);
	}
	.breakdown {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1) var(--kb-space-4);
		margin: 0;
		padding: 0;
		list-style: none;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		font-variant-numeric: tabular-nums;
	}
	.breakdown li {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
	}
	.breakdown i {
		width: 0.5rem;
		height: 0.5rem;
		border-radius: 50%;
	}
	i.learning {
		background: var(--kb-color-state-learning);
	}
	i.new {
		background: var(--kb-color-state-new);
	}
	i.review {
		background: var(--kb-color-state-review);
	}
	.go {
		display: flex;
		flex-direction: column;
		align-items: stretch;
		gap: var(--kb-space-2);
		flex: 1 1 14rem;
		max-width: 22rem;
	}
	.eta {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: var(--kb-space-1);
		margin: 0;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.done {
		display: flex;
		align-items: center;
		gap: var(--kb-space-5);
		min-width: 0;
		flex: 1 1 16rem;
	}
	.text {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		min-width: 0;
		flex: 1;
	}
	.enso {
		width: 4.5rem;
		height: 4.5rem;
		flex: none;
		color: var(--kb-color-brand);
	}
	.done h2 {
		margin: 0;
		font: var(--kb-type-title);
	}
	.countdown {
		margin: 0;
		font: var(--kb-type-title);
		font-variant-numeric: tabular-nums;
	}
	.muted {
		margin: 0;
		color: var(--kb-color-text-muted);
	}
	@media (max-width: 599.98px) {
		.go {
			max-width: none;
			flex-basis: 100%;
		}
		.enso {
			width: 3.5rem;
			height: 3.5rem;
		}
	}
</style>
