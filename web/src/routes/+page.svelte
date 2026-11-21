<script lang="ts">
	// Today: what is due now and one button to start; decks, recent activity and what is giving trouble around it.
	import { onDestroy } from 'svelte';
	import { goto } from '$app/navigation';
	import ActivityStrip from '../lib/ui/ActivityStrip.svelte';
	import Button from '../lib/ui/Button.svelte';
	import Chip from '../lib/ui/Chip.svelte';
	import EmptyState from '../lib/ui/EmptyState.svelte';
	import LoadError from '../lib/ui/LoadError.svelte';
	import Icon from '../lib/ui/Icon.svelte';
	import ListRow from '../lib/ui/ListRow.svelte';
	import Page from '../lib/ui/Page.svelte';
	import Panel from '../lib/ui/Panel.svelte';
	import Skeleton from '../lib/ui/Skeleton.svelte';
	import type { IconName } from '../lib/ui/icons';
	import { ut, type UiMessageKey } from '../lib/ui/messages';
	import { DecksListPage } from '../lib/state/decks.svelte';
	import { StatsPage, iso } from '../lib/state/stats.svelte';
	import { StrugglePage } from '../lib/state/struggle.svelte';
	import DeckProgress from '../lib/decks/DeckProgress.svelte';
	import LoopHero from '../lib/review/LoopHero.svelte';
	import { TodayQueue } from '../lib/state/today.svelte';
	import { noteTitle } from '../lib/ui/cardState';
	import { th } from './messages';

	const stats = new StatsPage();
	const decks = new DecksListPage();
	const struggle = new StrugglePage();
	const queue = new TodayQueue();
	// The stats load in parallel with the queue (LF-W2): they load only what this page shows, fail per widget, and
	// are aborted when the page is left. Every store keeps its data while it refetches, and refetches on
	// `sync.changed` and on `cache.updated` for its own operation.
	const HOME_STATS = ['summary', 'heatmap', 'retention'] as const;
	$effect(() => {
		decks.start();
		struggle.start();
		void queue.load();
		stats.start(HOME_STATS);
		const stops = [decks.watch(), struggle.watch(), queue.watch(), stats.watch(HOME_STATS)];
		return () => stops.forEach((stop) => stop());
	});
	onDestroy(() => {
		queue.dispose();
		stats.dispose();
	});

	const summary = $derived(stats.summary);
	// ADR-037: the hero reads `loop` only (the ready-now number is always `ready.total`, C3).
	const view = $derived(queue.view);
	const ready = $derived(view?.loop.ready.total ?? 0);
	const emptyAccount = $derived(!!summary && summary.totals.cards === 0 && decks.loaded && !decks.loadFailed && decks.items.length === 0);
	// RC-WEB: decide "empty account or not" before drawing either layout, so a late summary never swaps the page.
	// A loop with anything to do or a deck settle it at once (no waiting on the slow summary); a failed summary or
	// deck list means "not known to be empty".
	const accountKnown = $derived(
		(queue.loaded && !!view && view.phase !== 'empty') ||
			(decks.loaded && (decks.loadFailed || decks.items.length > 0)) ||
			!!summary ||
			stats.summaryP.failed
	);
	// Time for the ready cards: average answer time today, else ~10 s a card.
	const eta = $derived.by(() => {
		const d = summary?.doneToday;
		const perCard = d && d.reviews > 0 && d.timeMs > 0 ? d.timeMs / d.reviews : 10_000;
		const min = Math.round((ready * perCard) / 60_000);
		return min < 1 ? th('home.etaShort') : th('home.eta', { n: min });
	});

	/** Keep going on Home (C2): persisted for today's study day, then the review continues past the limits. */
	function keepGoing(): void {
		queue.keepGoing();
		void goto('/review');
	}

	const today = new Date();
	const dateLine = today.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });

	const recent = $derived.by(() => {
		const days = stats.heatmap?.days ?? [];
		const byDate = new Map(days.map((d) => [iso(d.date), d.reviews]));
		const end = stats.heatmap ? stats.heatmap.to : null;
		if (!end) return [];
		const out = [];
		for (let i = 13; i >= 0; i--) {
			const d = new Date(end.getTime() - i * 86_400_000);
			out.push({
				key: iso(d),
				label: d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }),
				short: d.toLocaleDateString(undefined, { weekday: 'narrow', timeZone: 'UTC' }),
				value: byDate.get(iso(d)) ?? 0
			});
		}
		return out;
	});

	const pct = (v: number | null | undefined): string => (v == null ? '–' : `${Math.round(v * 100)}%`);

	function nextDue(d: Date | null): string {
		if (!d) return th('home.nothingScheduled');
		const sameDay = d.toDateString() === new Date().toDateString();
		return sameDay
			? th('home.nextDue', { time: d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) })
			: th('home.nextDueDay', { date: d.toLocaleDateString(undefined, { weekday: 'long', hour: '2-digit', minute: '2-digit' }) });
	}

	const DECK_ICON: Record<string, IconName> = { user: 'decks', smart: 'search', list: 'browse', media: 'media' };
	const topDecks = $derived(decks.items.filter((d) => d.enabled).slice(0, 6));
</script>

<svelte:head><title>{th('home.title')} · Kotobaya</title></svelte:head>

<Page title={th('home.title')} eyebrow={dateLine}>
	{#if !accountKnown}
		<div class="layout" aria-busy="true">
			<div class="main">
				<div class="hero skeleton"><Skeleton shape="block" height="11rem" /></div>
				<Panel><Skeleton shape="row" count={4} /></Panel>
			</div>
			<div class="side"><Skeleton shape="block" height="9rem" /><Skeleton shape="block" height="7rem" /></div>
		</div>
	{:else if emptyAccount}
		<EmptyState size="page" art="sparkle" title={th('home.emptyTitle')} body={th('home.emptyBody')}>
			{#snippet action()}
				<div class="empty-actions">
					<Button variant="primary" icon="search" href="/dictionary">{th('home.emptySearch')}</Button>
					<Button variant="outline" icon="import" href="/import">{th('home.emptyImport')}</Button>
					<Button variant="quiet" icon="plus" href="/decks/new">{th('home.createDeck')}</Button>
				</div>
			{/snippet}
		</EmptyState>
	{:else}
		<div class="layout">
			<div class="main">
				{#if !queue.loaded}
					<div class="hero skeleton" aria-busy="true"><Skeleton shape="block" height="11rem" /></div>
				{:else}
				<section class="hero" aria-labelledby="due-title">
					{#if !view}
						<div class="due">
							<p class="overline" id="due-title">{th('home.dueNow')}</p>
							<p class="muted">{queue.uncached ? th('home.notDownloaded') : th('home.countsFailed')}</p>
							<div class="row-actions">
								<Button size="sm" variant="outline" icon="sync" onclick={() => void queue.load()}>{ut('app.retry')}</Button>
							</div>
						</div>
						<div class="go">
							<Button variant="primary" size="lg" href="/review" icon="review" full>{th('home.start')}</Button>
						</div>
					{:else}
						<LoopHero {view} {eta} onkeepgoing={keepGoing} />
						{#if queue.stale}<p class="muted updating" role="status" data-testid="home-updating">{th('home.updating')}</p>{/if}
						{#if view.phase === 'empty' && summary}<p class="muted next">{nextDue(summary.nextDueAt)}</p>{/if}
					{/if}
					{#if summary && summary.doneToday.reviews > 0}
						<p class="today">
							<span class="label">{th('home.doneToday')}</span>
							<span>{th('home.doneReviews', { n: summary.doneToday.reviews })}</span>
							<span>{th('home.doneRetention', { pct: pct(summary.doneToday.retention) })}</span>
							<span>{th('home.doneTime', { n: Math.round(summary.doneToday.timeMs / 60_000) })}</span>
						</p>
					{/if}
				</section>
				{/if}

				<Panel title={th('home.decks')} link={decks.items.length > 0 ? { href: '/decks', label: th('home.allDecks') } : undefined} flush>
					{#if !decks.loaded}
						<div class="pad"><Skeleton shape="row" count={4} /></div>
					{:else if decks.uncached}
						<p class="pad muted">{th('home.notDownloaded')}</p>
					{:else if decks.loadFailed}
						<LoadError onretry={() => decks.retry()} />
					{:else if topDecks.length === 0}
						<EmptyState art="decks" title={th('home.noDecks')} body={th('home.noDecksBody')}>
							{#snippet action()}<Button variant="tonal" icon="plus" href="/decks/new">{th('home.createDeck')}</Button>{/snippet}
						</EmptyState>
					{:else}
						{#if decks.stale}<p class="pad muted updating" role="status" data-testid="decks-updating">{th('home.updating')}</p>{/if}
						<div class="rows">
							{#each topDecks as d (d.id)}
								<div class="deckrow">
									<ListRow
										title={d.name}
										supporting={ut(`app.deckType.${d.type}` as UiMessageKey)}
										meta={th('home.deckItems', { n: d.itemCount.toLocaleString() })}
										href="/decks/{d.id}"
									>
										{#snippet leading()}<span class="disc"><Icon name={DECK_ICON[d.type] ?? 'decks'} size={18} /></span>{/snippet}
									</ListRow>
									{#if d.progress}<div class="prog"><DeckProgress deck={d} coverage /></div>{/if}
								</div>
							{/each}
						</div>
					{/if}
				</Panel>
			</div>

			<div class="side">
				<Panel title={th('home.activity')} link={{ href: '/stats', label: ut('app.nav.stats') }}>
					{#if stats.heatmap}
						<ActivityStrip caption={th('home.activityCaption')} days={recent} />
					{:else if stats.heatmapP.uncached}
						<p class="muted">{th('home.notDownloaded')}</p>
					{:else if stats.heatmapP.failed}
						<p class="muted inline-error">
							{ut('app.loadFailed')}
							<Button size="sm" variant="quiet" icon="sync" onclick={() => stats.retry('heatmap')}>{ut('app.retry')}</Button>
						</p>
					{:else}
						<Skeleton shape="block" height="3.5rem" />
					{/if}
					{#if stats.stale}<p class="muted updating" role="status" data-testid="stats-updating">{th('home.updating')}</p>{/if}
					<dl class="figures">
						<div><dt>{th('home.streak')}</dt><dd class="kb-num">{summary?.streakDays ?? '–'}</dd></div>
						<div><dt>{th('home.retention')}</dt><dd class="kb-num">{pct(stats.retention?.overall.retention)}</dd></div>
						<div><dt>{th('home.totalCards')}</dt><dd class="kb-num">{summary?.totals.cards.toLocaleString() ?? '–'}</dd></div>
					</dl>
					{#if stats.summaryP.failed || stats.retentionP.failed}
						<p class="muted small inline-error">
							{th('home.figuresFailed')}
							<Button
								size="sm"
								variant="quiet"
								icon="sync"
								onclick={() => {
									if (stats.summaryP.failed) stats.retry('summary');
									if (stats.retentionP.failed) stats.retry('retention');
								}}>{ut('app.retry')}</Button
							>
						</p>
					{/if}
				</Panel>

				<Panel title={th('home.struggle')} link={{ href: '/struggle', label: th('home.struggleLink') }}>
					{#if struggle.stale}<p class="muted updating" role="status" data-testid="struggle-updating">{th('home.updating')}</p>{/if}
					{#if !struggle.loaded}
						<Skeleton shape="text" count={2} />
					{:else if struggle.uncached}
						<p class="muted">{th('home.notDownloaded')}</p>
					{:else if struggle.words.length === 0}
						<p class="calm"><Icon name="success" size={20} />{th('home.struggleEmpty')}</p>
					{:else}
						<div class="chips">
							{#each struggle.words.slice(0, 6) as w (w.noteId)}
								<Chip
									kind="tag"
									lang="ja"
									label={w.entry?.headword ?? noteTitle(w.note)}
									onclick={() => goto(`/struggle/word/${w.noteId}`)}
								/>
							{/each}
						</div>
						{#if summary && summary.totals.leeches > 0}
							<p class="muted small">{th('home.leeches', { n: summary.totals.leeches })}</p>
						{/if}
					{/if}
				</Panel>
			</div>
		</div>
	{/if}
</Page>

<style>
	.deckrow + .deckrow {
		border-top: 1px solid var(--kb-color-border);
	}
	.prog {
		padding: 0 var(--kb-space-4) var(--kb-space-3);
	}
	.inline-error {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2);
	}
	.row-actions {
		display: flex;
		gap: var(--kb-space-2);
	}
	.layout {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: var(--kb-space-5);
	}
	.main,
	.side {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
		min-width: 0;
	}
	@media (min-width: 840px) {
		.layout {
			grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
			align-items: start;
		}
	}

	/* ---------- hero ---------- */
	.hero {
		display: flex;
		flex-wrap: wrap;
		align-items: flex-end;
		justify-content: space-between;
		gap: var(--kb-space-6);
		padding: var(--kb-space-6);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
		box-shadow: var(--kb-elevation-1);
	}
	.hero.skeleton {
		display: block;
		padding: 0;
		border: 0;
		box-shadow: none;
		background: none;
	}
	.due {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		min-width: 0;
	}
	.overline {
		font: var(--kb-type-overline);
		letter-spacing: var(--kb-type-overline-tracking);
		text-transform: uppercase;
		color: var(--kb-color-text-muted);
	}
	.go {
		display: flex;
		flex-direction: column;
		align-items: stretch;
		gap: var(--kb-space-2);
		flex: 1 1 14rem;
		max-width: 22rem;
	}
	.updating {
		margin: 0;
		width: 100%;
		font: var(--kb-type-caption);
	}
	.next {
		margin: 0;
		width: 100%;
		font: var(--kb-type-body-sm);
	}
	.today {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1) var(--kb-space-4);
		width: 100%;
		padding-top: var(--kb-space-4);
		border-top: 1px solid var(--kb-color-border);
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		font-variant-numeric: tabular-nums;
	}
	.today .label {
		color: var(--kb-color-text);
		font-weight: 600;
	}
	/* Phones: the button takes the full width, in thumb reach. */
	@media (max-width: 599.98px) {
		.hero {
			padding: var(--kb-space-5);
		}
		.go {
			max-width: none;
			flex-basis: 100%;
		}
	}

	/* ---------- panels ---------- */
	.pad {
		padding: 0 var(--kb-space-5);
	}
	.rows {
		display: flex;
		flex-direction: column;
	}
	.disc {
		display: grid;
		place-items: center;
		width: 2.25rem;
		height: 2.25rem;
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface-sunken);
		color: var(--kb-color-text-muted);
	}
	.figures {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: var(--kb-space-3);
		margin: var(--kb-space-5) 0 0;
		align-items: start;
	}
	@container (max-width: 18rem) {
		.figures {
			grid-template-columns: minmax(0, 1fr);
		}
	}
	.figures div {
		display: flex;
		flex-direction: column-reverse;
		justify-content: flex-end;
		gap: 2px;
		min-width: 0;
	}
	dt {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	dd {
		margin: 0;
		font: var(--kb-type-title);
		font-weight: 600;
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
	}
	.calm {
		display: flex;
		align-items: center;
		gap: var(--kb-space-2);
		color: var(--kb-color-text-muted);
	}
	.calm :global(svg) {
		color: var(--kb-color-grade-good);
		flex: none;
	}
	.muted {
		color: var(--kb-color-text-muted);
	}
	.small {
		margin-top: var(--kb-space-3);
		font: var(--kb-type-body-sm);
	}
	.empty-actions {
		display: flex;
		flex-wrap: wrap;
		justify-content: center;
		gap: var(--kb-space-2);
	}
</style>
