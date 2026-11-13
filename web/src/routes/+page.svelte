<script lang="ts">
	// Today: what is due now and one button to start; decks, recent activity and what is giving trouble around it.
	import { goto } from '$app/navigation';
	import ActivityStrip from '../lib/ui/ActivityStrip.svelte';
	import Banner from '../lib/ui/Banner.svelte';
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
	import { noteTitle } from '../lib/ui/cardState';
	import { th } from './messages';

	const stats = new StatsPage();
	const decks = new DecksListPage();
	const struggle = new StrugglePage();
	$effect(() => {
		stats.start();
		decks.start();
		struggle.start();
	});

	const summary = $derived(stats.summary);
	const loading = $derived(!summary && !stats.summaryP.failed);
	const due = $derived(summary ? summary.due._new + summary.due.learning + summary.due.review : 0);
	const emptyAccount = $derived(!!summary && summary.totals.cards === 0 && decks.loaded && !decks.loadFailed && decks.items.length === 0);
	// Average answer time today, else ~10 s a card.
	const etaMin = $derived.by(() => {
		if (!summary) return 0;
		const d = summary.doneToday;
		const perCard = d.reviews > 0 && d.timeMs > 0 ? d.timeMs / d.reviews : 10_000;
		return Math.round((due * perCard) / 60_000);
	});

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
	{#if stats.summaryP.failed}
		<div class="error">
			<Banner tone="error" title={th('home.errorTitle')}>
				{th('home.errorBody')}
				{#snippet action()}<Button size="sm" variant="outline" onclick={() => stats.start()}>{ut('app.retry')}</Button>{/snippet}
			</Banner>
		</div>
	{/if}

	{#if loading}
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
				<section class="hero" aria-labelledby="due-title">
					{#if summary && due > 0}
						<div class="due">
							<p class="overline" id="due-title">{th('home.dueNow')}</p>
							<p class="figure">
								<span class="n kb-num">{due.toLocaleString()}</span>
								<span class="unit">{due === 1 ? th('home.card') : th('home.cards')}</span>
							</p>
							<ul class="breakdown">
								<li><i class="new"></i>{th('home.new', { n: summary.due._new })}</li>
								<li><i class="learning"></i>{th('home.learning', { n: summary.due.learning })}</li>
								<li><i class="review"></i>{th('home.review', { n: summary.due.review })}</li>
							</ul>
						</div>
						<div class="go">
							<Button variant="primary" size="lg" href="/review" icon="review" full>{th('home.start')}</Button>
							<p class="eta"><Icon name="clock" size={16} />{etaMin < 1 ? th('home.etaShort') : th('home.eta', { n: etaMin })}</p>
						</div>
					{:else if summary}
						<div class="done">
							<svg class="enso" viewBox="0 0 120 120" aria-hidden="true">
								<path
									d="M93 28c-9-9-21-13-34-12C33 18 15 38 16 62c1 24 21 43 46 42 24-1 42-20 42-43 0-9-3-17-7-24"
									fill="none"
									stroke="currentColor"
									stroke-width="8"
									stroke-linecap="round"
								/>
							</svg>
							<div>
								<h2 id="due-title">{th('home.doneTitle')}</h2>
								<p>{nextDue(summary.nextDueAt)}</p>
							</div>
						</div>
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

				<Panel title={th('home.decks')} link={decks.items.length > 0 ? { href: '/decks', label: th('home.allDecks') } : undefined} flush>
					{#if !decks.loaded}
						<div class="pad"><Skeleton shape="row" count={4} /></div>
					{:else if decks.loadFailed}
						<LoadError onretry={() => decks.retry()} />
					{:else if topDecks.length === 0}
						<EmptyState art="decks" title={th('home.noDecks')} body={th('home.noDecksBody')}>
							{#snippet action()}<Button variant="tonal" icon="plus" href="/decks/new">{th('home.createDeck')}</Button>{/snippet}
						</EmptyState>
					{:else}
						<div class="rows">
							{#each topDecks as d (d.id)}
								<ListRow
									title={d.name}
									supporting={ut(`app.deckType.${d.type}` as UiMessageKey)}
									meta={th('home.deckItems', { n: d.itemCount.toLocaleString() })}
									href="/decks/{d.id}"
								>
									{#snippet leading()}<span class="disc"><Icon name={DECK_ICON[d.type] ?? 'decks'} size={18} /></span>{/snippet}
								</ListRow>
							{/each}
						</div>
					{/if}
				</Panel>
			</div>

			<div class="side">
				<Panel title={th('home.activity')} link={{ href: '/stats', label: ut('app.nav.stats') }}>
					{#if stats.heatmap}
						<ActivityStrip caption={th('home.activityCaption')} days={recent} />
					{:else if stats.heatmapP.failed}
						<p class="muted">{ut('app.loadFailed')}</p>
					{:else}
						<Skeleton shape="block" height="3.5rem" />
					{/if}
					<dl class="figures">
						<div><dt>{th('home.streak')}</dt><dd class="kb-num">{summary?.streakDays ?? '–'}</dd></div>
						<div><dt>{th('home.retention')}</dt><dd class="kb-num">{pct(stats.retention?.overall.retention)}</dd></div>
						<div><dt>{th('home.totalCards')}</dt><dd class="kb-num">{summary?.totals.cards.toLocaleString() ?? '–'}</dd></div>
					</dl>
				</Panel>

				<Panel title={th('home.struggle')} link={{ href: '/struggle', label: th('home.struggleLink') }}>
					{#if !struggle.loaded}
						<Skeleton shape="text" count={2} />
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
	.error {
		margin-bottom: var(--kb-space-5);
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
	.figure {
		display: flex;
		align-items: baseline;
		gap: var(--kb-space-2);
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
	i.new {
		background: var(--kb-color-state-new);
	}
	i.learning {
		background: var(--kb-color-state-learning);
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
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.done {
		display: flex;
		align-items: center;
		gap: var(--kb-space-5);
	}
	.enso {
		width: 4.5rem;
		height: 4.5rem;
		flex: none;
		color: var(--kb-color-brand);
	}
	.done h2 {
		font: var(--kb-type-title);
	}
	.done p {
		color: var(--kb-color-text-muted);
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
