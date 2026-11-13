<script lang="ts">
	// /styleguide (UI-DS): every token and component of the design system, components in both themes side by side.
	// Static: no state module, no data. docs/DESIGN.md is the prose companion.
	import '../../lib/ui/fonts';
	import { onMount } from 'svelte';
	import AppShell from '../../lib/ui/AppShell.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import Chip from '../../lib/ui/Chip.svelte';
	import DataTable from '../../lib/ui/DataTable.svelte';
	import Dialog from '../../lib/ui/Dialog.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import GradeBar from '../../lib/ui/GradeBar.svelte';
	import IconButton from '../../lib/ui/IconButton.svelte';
	import ListRow from '../../lib/ui/ListRow.svelte';
	import PitchAccent from '../../lib/ui/PitchAccent.svelte';
	import ProgressBar from '../../lib/ui/ProgressBar.svelte';
	import SegmentedControl from '../../lib/ui/SegmentedControl.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import Surface from '../../lib/ui/Surface.svelte';
	import Tabs from '../../lib/ui/Tabs.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import Toast from '../../lib/ui/Toast.svelte';
	import Kbd from '../../lib/ui/Kbd.svelte';
	import { CARD_STATES, DECK_COLORS, type CardState, type DeckColor, type NavItem, type TableColumn } from '../../lib/ui/kinds';
	import { ut, type UiMessageKey } from '../../lib/ui/messages';
	import { initTheme, setTheme, theme, type ThemePreference } from '../../lib/ui/theme.svelte';
	import { tokens } from '../../lib/ui/tokens.generated';

	onMount(initTheme);

	const themeOptions: { value: ThemePreference; label: string; icon: 'system' | 'sun' | 'moon' }[] = [
		{ value: 'system', label: ut('ui.theme.system'), icon: 'system' },
		{ value: 'light', label: ut('ui.theme.light'), icon: 'sun' },
		{ value: 'dark', label: ut('ui.theme.dark'), icon: 'moon' }
	];
	let themeChoice = $state<ThemePreference>('system');
	$effect(() => {
		themeChoice = theme.preference;
	});

	const sections: { id: string; key: UiMessageKey }[] = [
		{ id: 'colour', key: 'sg.section.colour' },
		{ id: 'contrast', key: 'sg.section.contrast' },
		{ id: 'type', key: 'sg.section.type' },
		{ id: 'space', key: 'sg.section.space' },
		{ id: 'motion', key: 'sg.section.motion' },
		{ id: 'components', key: 'sg.section.components' },
		{ id: 'review', key: 'sg.section.review' },
		{ id: 'shell', key: 'sg.section.shell' }
	];

	// ---- colour ----
	type ColorRow = (typeof tokens.colors)[number];
	const colorGroups: { key: UiMessageKey; test: (n: string) => boolean }[] = [
		{ key: 'sg.colour.group.surface', test: (n) => /^(bg|surface|border|scrim)/.test(n) },
		{ key: 'sg.colour.group.text', test: (n) => n.startsWith('text') },
		{ key: 'sg.colour.group.action', test: (n) => /^(on-)?primary|^(link|focus|brand)$/.test(n) },
		{ key: 'sg.colour.group.feedback', test: (n) => /^(on-)?(danger|warning|success)/.test(n) },
		{ key: 'sg.colour.group.grade', test: (n) => n.startsWith('grade-') },
		{ key: 'sg.colour.group.state', test: (n) => n.startsWith('state-') },
		{ key: 'sg.colour.group.pitch', test: (n) => /^(pitch-|highlight|on-highlight)/.test(n) },
		{ key: 'sg.colour.group.deck', test: (n) => n.startsWith('deck-') }
	];
	const grouped = colorGroups.map((g) => ({ key: g.key, rows: tokens.colors.filter((c) => g.test(c.name)) }));
	const hex = Object.fromEntries(tokens.colors.map((c) => [c.name, c])) as Record<string, ColorRow>;

	// ---- contrast ----
	type Pair = (typeof tokens.contrast)[number];
	let contrastFilter = $state<'tight' | 'all'>('tight');
	const margin = (p: Pair): number => Math.min(p.light, p.dark) - p.min;
	const pairs = $derived(
		[...tokens.contrast].sort((a, b) => margin(a) - margin(b)).filter((p) => contrastFilter === 'all' || margin(p) < 1.6)
	);
	const contrastColumns: TableColumn[] = [
		{ key: 'fg', label: ut('sg.contrast.fg') },
		{ key: 'bg', label: ut('sg.contrast.bg') },
		{ key: 'kind', label: ut('sg.contrast.kind'), width: '6.5rem', optional: true },
		{ key: 'min', label: ut('sg.contrast.min'), width: '4.5rem', align: 'end', optional: true },
		{ key: 'light', label: ut('sg.contrast.light'), width: '8.5rem', align: 'end' },
		{ key: 'dark', label: ut('sg.contrast.dark'), width: '8.5rem', align: 'end' }
	];

	// ---- type ----
	const specimens: Record<string, { text: string; lang?: string }> = {
		'jp-hero': { text: '鬱', lang: 'ja' },
		'jp-display': { text: '一期一会', lang: 'ja' },
		display: { text: '1,284' },
		headline: { text: '今日の復習 · Today', lang: 'ja' },
		title: { text: '取る — to take', lang: 'ja' },
		'title-sm': { text: '叶う · かなう', lang: 'ja' },
		'jp-body': { text: '吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。', lang: 'ja' },
		reading: { text: 'いちごいちえ', lang: 'ja' },
		'body-lg': { text: ut('sg.demo.gloss.ichigo') },
		body: { text: ut('sg.demo.surfaceBody') },
		'body-sm': { text: ut('sg.demo.deckHint') },
		label: { text: ut('sg.demo.primary') },
		caption: { text: '<1m · 6m · 1d · 4d' },
		overline: { text: ut('sg.demo.today') }
	};

	// ---- motion ----
	let played = $state(false);

	// ---- component demo state ----
	let browseMode = $state<'cards' | 'notes'>('cards');
	let range = $state<'today' | 'week' | 'month' | 'year'>('week');
	let tab = $state<'overview' | 'senses' | 'kanji' | 'examples'>('senses');
	let filterDue = $state(true);
	let filterAudio = $state(false);
	let filterLeech = $state(false);
	let query = $state('取る');
	let deckName = $state('Core 2k');
	let limit = $state('20');
	let dialogOpen = $state(false);
	let selectedRow = $state('2');
	let sort = $state<{ key: string; dir: 'asc' | 'desc' }>({ key: 'due', dir: 'asc' });
	let lastGrade = $state('');

	const deckLabels: Record<DeckColor, string> = {
		ai: ut('sg.demo.deckCore'),
		shu: ut('sg.demo.deckNovel'),
		matcha: ut('sg.demo.deckGrammar'),
		kohaku: ut('sg.demo.deckKanji'),
		fuji: 'Fuji',
		sora: 'Sora',
		sakura: 'Sakura',
		aoi: 'Aoi',
		hai: 'Hai'
	};
	const stateLabel = (s: CardState): string => ut(`ui.state.${s}`);

	interface DemoCard {
		id: string;
		word: string;
		reading: string;
		deck: DeckColor;
		state: CardState;
		due: string;
		lapses: number;
	}
	const demoCards: DemoCard[] = [
		{ id: '1', word: '取る', reading: 'とる', deck: 'ai', state: 'review', due: '2026-11-14', lapses: 1 },
		{ id: '2', word: '叶う', reading: 'かなう', deck: 'ai', state: 'learning', due: '2026-11-13', lapses: 0 },
		{ id: '3', word: '一期一会', reading: 'いちごいちえ', deck: 'shu', state: 'new', due: '—', lapses: 0 },
		{ id: '4', word: '鬱陶しい', reading: 'うっとうしい', deck: 'kohaku', state: 'relearning', due: '2026-11-13', lapses: 4 },
		{ id: '5', word: '承る', reading: 'うけたまわる', deck: 'matcha', state: 'leech', due: '2026-11-20', lapses: 9 },
		{ id: '6', word: '所謂', reading: 'いわゆる', deck: 'ai', state: 'suspended', due: '—', lapses: 2 }
	];
	const tableColumns: TableColumn[] = [
		{ key: 'word', label: ut('sg.demo.colWord'), lang: 'ja', sortable: true },
		{ key: 'reading', label: ut('sg.demo.colReading'), lang: 'ja', optional: true },
		{ key: 'deck', label: ut('sg.demo.colDeck'), optional: true },
		{ key: 'state', label: ut('sg.demo.colState'), width: '9rem' },
		{ key: 'due', label: ut('sg.demo.colDue'), width: '8rem', sortable: true, align: 'end', optional: true },
		{ key: 'lapses', label: ut('sg.demo.colLapses'), width: '6rem', sortable: true, align: 'end' }
	];
	const sortedCards = $derived(
		[...demoCards].sort((a, b) => {
			const k = sort.key as keyof DemoCard;
			const r = String(a[k]).localeCompare(String(b[k]), 'ja', { numeric: true });
			return sort.dir === 'asc' ? r : -r;
		})
	);
	function onsort(key: string): void {
		sort = sort.key === key ? { key, dir: sort.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' };
	}

	const navItems: NavItem[] = [
		{ href: '/', label: ut('sg.nav.home'), icon: 'home' },
		{ href: '/review', label: ut('sg.nav.review'), icon: 'review' },
		{ href: '/dictionary', label: ut('sg.nav.dictionary'), icon: 'dictionary' },
		{ href: '/cards', label: ut('sg.nav.browse'), icon: 'browse' },
		{ href: '/decks', label: ut('sg.nav.decks'), icon: 'decks' },
		{ href: '/struggle', label: ut('sg.nav.struggle'), icon: 'struggle' },
		{ href: '/stats', label: ut('sg.nav.stats'), icon: 'stats' },
		{ href: '/media', label: ut('sg.nav.media'), icon: 'media' },
		{ href: '/import', label: ut('sg.nav.import'), icon: 'import', secondary: true },
		{ href: '/note-types', label: ut('sg.nav.noteTypes'), icon: 'noteTypes', secondary: true },
		{ href: '/settings', label: ut('sg.nav.settings'), icon: 'settings', secondary: true }
	];
	const intervals = { again: '<1m', hard: '6m', good: '1d', easy: '4d' };
</script>

<svelte:head><title>{ut('sg.title')} · {ut('sg.brand')}</title></svelte:head>

{#snippet swatch(c: ColorRow)}
	<li class="swatch">
		<span class="chips" aria-hidden="true">
			<span style:background={c.light}></span>
			<span style:background={c.dark}></span>
		</span>
		<span class="sw-text">
			<code>--kb-color-{c.name}</code>
			<span class="hexes">{c.light.toLowerCase()} · {c.dark.toLowerCase()}</span>
			{#if c.use}<span class="use">{c.use}</span>{/if}
		</span>
	</li>
{/snippet}

{#snippet sample(fg: string, bg: string, theme: 'light' | 'dark')}
	<span class="sample" style:color={hex[fg][theme]} style:background={hex[bg][theme]} aria-hidden="true">Aa あ</span>
{/snippet}

{#snippet reviewCard(back: boolean)}
	<div class="kb-card kb-vocab {back ? 'kb-back' : 'kb-front'}" lang="ja">
		<div class="kb-front"><div class="kb-expression"><ruby>取<rt>と</rt></ruby>る</div></div>
		{#if back}
			<hr class="kb-divider" />
			<div class="kb-reading">
				<span class="kb-pitch"><span class="kb-mora kb-pitch-high kb-pitch-drop">と</span><span class="kb-mora kb-pitch-low">る</span></span>
				<button class="kb-audio" type="button" aria-label={ut('sg.demo.playAudio')}></button>
			</div>
			<div class="kb-meaning" lang="en">{ut('sg.demo.gloss.toru')}</div>
			<div class="kb-sentence">新聞を<span class="kb-spot">取って</span>ください。</div>
			<div class="kb-translation" lang="en">Please get me the newspaper.</div>
		{/if}
	</div>
{/snippet}

{#snippet reviewScreen(back: boolean)}
	<div class="review">
		<div class="review-top">
			<span class="review-deck"><Chip kind="deck" deck="ai" label={ut('sg.demo.reviewDeck')} /></span>
			<ProgressBar value={12} max={85} label={ut('sg.section.review')} showCount />
		</div>
		<div class="review-card">{@render reviewCard(back)}</div>
		<div class="review-actions">
			{#if back}
				<GradeBar {intervals} ongrade={(g) => (lastGrade = g)} />
			{:else}
				<Button variant="primary" size="lg" full>{ut('sg.demo.showAnswer')} <Kbd keys="Space" /></Button>
			{/if}
		</div>
	</div>
{/snippet}

{#snippet gallery()}
	<div class="block">
		<h3>{ut('sg.comp.buttons')}</h3>
		<div class="row">
			<Button variant="primary" icon="review">{ut('sg.demo.primary')}</Button>
			<Button variant="tonal" icon="plus">{ut('sg.demo.tonal')}</Button>
			<Button variant="outline" icon="edit">{ut('sg.demo.outline')}</Button>
			<Button variant="quiet">{ut('sg.demo.quiet')}</Button>
			<Button variant="danger" icon="trash">{ut('sg.demo.danger')}</Button>
		</div>
		<div class="row">
			<Button variant="primary" loading>{ut('sg.demo.loading')}</Button>
			<Button variant="primary" disabled>{ut('sg.demo.disabled')}</Button>
			<Button variant="outline" size="sm">{ut('sg.demo.small')}</Button>
			<Button variant="outline" size="sm" icon="sync">{ut('sg.demo.loading')}</Button>
		</div>
		<Button variant="primary" size="lg" full iconEnd="chevronRight">{ut('sg.demo.large')}</Button>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.iconButtons')}</h3>
		<div class="row">
			<IconButton icon="audio" label={ut('sg.demo.playAudio')} />
			<IconButton icon="edit" label={ut('sg.demo.outline')} />
			<IconButton icon="flag" label={ut('ui.state.leech')} pressed />
			<IconButton icon="trash" label={ut('sg.demo.delete')} variant="outline" />
			<IconButton icon="plus" label={ut('sg.demo.tonal')} variant="tonal" />
			<IconButton icon="more" label={ut('ui.more')} />
		</div>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.segmented')}</h3>
		<SegmentedControl
			label={ut('sg.nav.browse')}
			options={[
				{ value: 'cards', label: ut('sg.demo.cards'), icon: 'review' },
				{ value: 'notes', label: ut('sg.demo.notes'), icon: 'edit' }
			]}
			bind:value={browseMode}
		/>
		<SegmentedControl
			label={ut('sg.section.motion')}
			full
			options={[
				{ value: 'today', label: ut('sg.demo.today') },
				{ value: 'week', label: ut('sg.demo.week') },
				{ value: 'month', label: ut('sg.demo.month') },
				{ value: 'year', label: ut('sg.demo.year') }
			]}
			bind:value={range}
		/>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.tabs')}</h3>
		<Tabs
			label={ut('sg.nav.dictionary')}
			tabs={[
				{ value: 'overview', label: ut('sg.demo.tabOverview') },
				{ value: 'senses', label: ut('sg.demo.tabSenses'), count: 12 },
				{ value: 'kanji', label: ut('sg.demo.tabKanji'), count: 2 },
				{ value: 'examples', label: ut('sg.demo.tabExamples') }
			]}
			bind:value={tab}
		/>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.chips')}</h3>
		<div class="row">
			<Chip kind="filter" label={ut('sg.demo.filterDue')} selected={filterDue} onclick={() => (filterDue = !filterDue)} />
			<Chip kind="filter" label={ut('sg.demo.filterAudio')} selected={filterAudio} onclick={() => (filterAudio = !filterAudio)} />
			<Chip kind="filter" label={ut('sg.demo.filterLeech')} selected={filterLeech} onclick={() => (filterLeech = !filterLeech)} />
			<Chip label="JLPT N3" onremove={() => {}} />
		</div>
		<div class="row">
			{#each CARD_STATES as s (s)}<Chip kind="state" state={s} label={stateLabel(s)} />{/each}
		</div>
		<div class="row">
			{#each DECK_COLORS as d (d)}<Chip kind="deck" deck={d} label={deckLabels[d]} />{/each}
		</div>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.inputs')}</h3>
		<TextField label={ut('sg.demo.searchLabel')} hideLabel icon="search" clearable placeholder={ut('sg.demo.search')} bind:value={query} lang="ja" />
		<div class="grid2">
			<TextField label={ut('sg.demo.deckName')} hint={ut('sg.demo.deckHint')} bind:value={deckName} />
			<TextField label={ut('sg.demo.deckName')} error={ut('sg.demo.deckError')} value="Core 2k" />
			<TextField label={ut('sg.demo.limit')} type="number" bind:value={limit} inputmode="numeric" />
			<TextField label={ut('sg.demo.limit')} value="20" disabled />
		</div>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.list')}</h3>
		<Surface padding="sm">
			<ListRow title="取る" titleLang="ja" supporting="とる · {ut('sg.demo.gloss.toru')}" supportingLang="ja" meta={ut('sg.demo.dueSoon')} onclick={() => {}}>
				{#snippet trailing()}<Chip kind="state" state="review" label={stateLabel('review')} />{/snippet}
			</ListRow>
			<ListRow title="叶う" titleLang="ja" supporting="かなう · {ut('sg.demo.gloss.kanau')}" selected onclick={() => {}}>
				{#snippet trailing()}<Chip kind="state" state="learning" label={stateLabel('learning')} />{/snippet}
			</ListRow>
			<ListRow title={ut('sg.demo.deckKanji')} supporting="2,041 · 85 due" href="#components">
				{#snippet leading()}<span class="deck-dot" style:background="var(--kb-color-deck-kohaku)"></span>{/snippet}
			</ListRow>
		</Surface>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.surface')}</h3>
		<div class="grid2">
			<Surface elevation={1}>
				<p class="eyebrow">{ut('sg.demo.surfaceTitle')}</p>
				<p class="big">85</p>
				<p class="muted">{ut('sg.demo.surfaceBody')}</p>
			</Surface>
			<Surface tone="raised" elevation={2}>
				<p class="eyebrow">{ut('sg.nav.decks')}</p>
				<p class="muted">{ut('sg.demo.noDecksBody')}</p>
			</Surface>
		</div>
		<Surface tone="sunken"><p class="muted" lang="ja">{ut('sg.demo.surfaceSunken')}</p></Surface>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.grades')}</h3>
		<GradeBar {intervals} ongrade={(g) => (lastGrade = g)} />
		{#if lastGrade}<p class="muted small">→ {lastGrade}</p>{/if}
	</div>

	<div class="block">
		<h3>{ut('sg.comp.progress')}</h3>
		<ProgressBar value={12} max={85} label={ut('sg.section.review')} showCount />
		<ProgressBar value={85} max={85} label={ut('sg.section.review')} showCount />
	</div>

	<div class="block">
		<h3>{ut('sg.comp.pitch')}</h3>
		<div class="row wide">
			<PitchAccent morae={['は', 'し']} downstep={0} />
			<PitchAccent morae={['は', 'し']} downstep={1} />
			<PitchAccent morae={['こ', 'こ', 'ろ']} downstep={2} />
			<PitchAccent morae={['お', 'と', 'う', 'と']} downstep={4} />
		</div>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.toasts')}</h3>
		<div class="stack">
			<Toast tone="success" message={ut('sg.demo.toastSaved')} ondismiss={() => {}} />
			<Toast message={ut('sg.demo.toastUndo')} action={{ label: ut('sg.demo.toastUndoAction'), onclick: () => {} }} ondismiss={() => {}} />
			<Toast tone="warning" message={ut('sg.demo.toastOffline')} />
			<Toast tone="error" message={ut('sg.demo.toastError')} ondismiss={() => {}} />
		</div>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.dialog')}</h3>
		<Dialog inline title={ut('sg.demo.dialogTitle')}>
			{ut('sg.demo.dialogBody')}
			{#snippet actions()}
				<Button variant="quiet">{ut('sg.demo.cancel')}</Button>
				<Button variant="danger">{ut('sg.demo.delete')}</Button>
			{/snippet}
		</Dialog>
		<div class="row">
			<Button variant="outline" onclick={() => (dialogOpen = true)}>{ut('sg.demo.dialogOpen')}</Button>
		</div>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.states')}</h3>
		<div class="grid2">
			<Surface padding="none">
				<EmptyState art="enso" title={ut('sg.demo.emptyTitle')} body={ut('sg.demo.emptyBody')}>
					{#snippet action()}<Button variant="tonal">{ut('sg.demo.emptyAction')}</Button>{/snippet}
				</EmptyState>
			</Surface>
			<Surface padding="none">
				<EmptyState art="decks" title={ut('sg.demo.noDecksTitle')} body={ut('sg.demo.noDecksBody')}>
					{#snippet action()}<Button variant="primary" icon="plus">{ut('sg.demo.noDecksAction')}</Button>{/snippet}
				</EmptyState>
			</Surface>
			<Surface padding="none">
				<EmptyState tone="error" art="alert" title={ut('sg.demo.errorTitle')} body={ut('sg.demo.errorBody')}>
					{#snippet action()}<Button variant="outline" icon="sync">{ut('sg.demo.retry')}</Button>{/snippet}
				</EmptyState>
			</Surface>
			<Surface padding="sm" label={ut('ui.loading')}>
				<Skeleton shape="row" count={3} />
				<div class="pad"><Skeleton shape="text" count={3} /></div>
			</Surface>
		</div>
	</div>

	<div class="block">
		<h3>{ut('sg.comp.table')}</h3>
		<DataTable
			columns={tableColumns}
			rows={sortedCards}
			rowKey={(r) => r.id}
			caption={ut('sg.demo.tableCaption')}
			{sort}
			{onsort}
			selected={selectedRow}
			onselect={(r) => (selectedRow = r.id)}
		>
			{#snippet cell(row, col)}
				{#if col.key === 'state'}
					<Chip kind="state" state={row.state} label={stateLabel(row.state)} />
				{:else if col.key === 'deck'}
					<span class="deck-cell"><span class="deck-dot" style:background="var(--kb-color-deck-{row.deck})"></span>{deckLabels[row.deck]}</span>
				{:else}
					{String(row[col.key as keyof DemoCard])}
				{/if}
			{/snippet}
		</DataTable>
		<div class="grid2">
			<div>
				<p class="muted small">{ut('sg.demo.tableLoading')}</p>
				<DataTable columns={tableColumns.slice(0, 3)} rows={[] as DemoCard[]} rowKey={(r) => r.id} caption={ut('sg.demo.tableLoading')} loading />
			</div>
			<div>
				<p class="muted small">{ut('sg.demo.tableEmpty')}</p>
				<DataTable columns={tableColumns.slice(0, 3)} rows={[] as DemoCard[]} rowKey={(r) => r.id} caption={ut('sg.demo.tableEmpty')} />
			</div>
		</div>
	</div>
{/snippet}

<div class="sg">
	<header class="hero">
		<div class="hero-inner">
			<span class="seal" aria-hidden="true">言</span>
			<div class="hero-text">
				<p class="eyebrow">{ut('sg.brand')}</p>
				<h1>{ut('sg.title')}</h1>
				<p class="lead">{ut('sg.lead')}</p>
			</div>
			<div class="hero-theme">
				<SegmentedControl label={ut('ui.theme')} options={themeOptions} bind:value={themeChoice} onchange={(v) => setTheme(v)} />
			</div>
		</div>
	</header>

	<div class="layout">
		<nav class="toc" aria-label={ut('sg.toc')}>
			<p class="eyebrow">{ut('sg.toc')}</p>
			<ul>
				{#each sections as s (s.id)}<li><a href="#{s.id}">{ut(s.key)}</a></li>{/each}
			</ul>
		</nav>

		<div class="sections">
			<section id="colour">
				<h2>{ut('sg.section.colour')}</h2>
				<p class="lead">{ut('sg.colour.lead')}</p>
				{#each grouped as g (g.key)}
					<h3>{ut(g.key)}</h3>
					<ul class="swatches">
						{#each g.rows as c (c.name)}{@render swatch(c)}{/each}
					</ul>
				{/each}
			</section>

			<section id="contrast">
				<h2>{ut('sg.section.contrast')}</h2>
				<p class="lead">{ut('sg.contrast.lead')}</p>
				<SegmentedControl
					label={ut('sg.section.contrast')}
					options={[
						{ value: 'tight', label: ut('sg.contrast.filter.tight') },
						{ value: 'all', label: ut('sg.contrast.filter.all') }
					]}
					bind:value={contrastFilter}
				/>
				<DataTable columns={contrastColumns} rows={pairs} rowKey={(p) => `${p.fg}|${p.bg}`} caption={ut('sg.section.contrast')} density="compact">
					{#snippet cell(p, col)}
						{#if col.key === 'light' || col.key === 'dark'}
							<span class="ratio">{@render sample(p.fg, p.bg, col.key)}{p[col.key].toFixed(2)}</span>
						{:else if col.key === 'min'}
							{p.min}
						{:else if col.key === 'kind'}
							{p.kind}
						{:else}
							<code>{col.key === 'fg' ? p.fg : p.bg}</code>
						{/if}
					{/snippet}
				</DataTable>
			</section>

			<section id="type">
				<h2>{ut('sg.section.type')}</h2>
				<p class="lead">{ut('sg.type.lead')}</p>
				<ul class="type-list">
					{#each tokens.typeRoles as r (r.name)}
						<li>
							<div class="type-meta">
								<code>--kb-type-{r.name}</code>
								<span class="muted small">{ut('sg.type.range', { min: r.minRem, max: r.maxRem, sp: r.sp, lh: r.lh })} · {r.weight} · {r.family}</span>
								<span class="muted small">{r.use}</span>
							</div>
							<p
								class="specimen"
								class:upper={r.name === 'overline'}
								lang={specimens[r.name]?.lang}
								style:font="var(--kb-type-{r.name})"
								style:letter-spacing="var(--kb-type-{r.name}-tracking)"
							>
								{specimens[r.name]?.text}
							</p>
						</li>
					{/each}
				</ul>
				<div class="grid2">
					<Surface>
						<p class="eyebrow">{ut('sg.type.ruby')}</p>
						<p class="ruby-demo" lang="ja">
							<ruby>一<rt>いち</rt>期<rt>ご</rt>一<rt>いち</rt>会<rt>え</rt></ruby>
						</p>
						<p class="jp-body ruby-lines" lang="ja">
							<ruby>新聞<rt>しんぶん</rt></ruby>を<ruby>取<rt>と</rt></ruby>ってください。<ruby>願<rt>ねが</rt></ruby>いが<ruby>叶<rt>かな</rt></ruby>う<ruby>日<rt>ひ</rt></ruby>まで、<ruby>毎日<rt>まいにち</rt></ruby><ruby>少<rt>すこ</rt></ruby>しずつ<ruby>練習<rt>れんしゅう</rt></ruby>する。
						</p>
						<p class="muted small">{ut('sg.type.rubyNote')}</p>
					</Surface>
					<Surface>
						<p class="eyebrow">{ut('sg.type.kana')}</p>
						<p class="jp-body" lang="ja">
							春はあけぼの。やうやう白くなりゆく山ぎは、すこしあかりて、紫だちたる雲のほそくたなびきたる。夏は夜。月のころはさらなり、やみもなほ、ほたるの多く飛びちがひたる。
						</p>
						<p class="muted small">{ut('sg.type.kanaNote')}</p>
					</Surface>
				</div>
			</section>

			<section id="space">
				<h2>{ut('sg.section.space')}</h2>
				<h3>{ut('sg.space.space')}</h3>
				<ul class="space-list">
					{#each tokens.space as s (s.name)}
						<li><code>--kb-space-{s.name}</code><span class="bar" style:width="{s.px}px"></span><span class="muted small">{s.px}px</span></li>
					{/each}
				</ul>
				<h3>{ut('sg.space.radius')}</h3>
				<div class="row wide">
					{#each tokens.radius as r (r.name)}
						<div class="radius-item">
							<span class="radius" style:border-radius="var(--kb-radius-{r.name})"></span>
							<code>{r.name}</code>
							<span class="muted small">{r.px === 9999 ? '∞' : `${r.px}px`}</span>
						</div>
					{/each}
				</div>
				<h3>{ut('sg.space.elevation')}</h3>
				<div class="row wide">
					{#each tokens.elevation as e (e.name)}
						<div class="elev" style:box-shadow="var(--kb-elevation-{e.name})">
							<code>--kb-elevation-{e.name}</code><span class="muted small">{e.dp} dp</span>
						</div>
					{/each}
				</div>
				<h3>{ut('sg.space.size')}</h3>
				<ul class="size-list">
					{#each tokens.size as s (s.name)}
						<li><code>--kb-size-{s.name}</code><span>{s.px}px · {s.dp}dp</span><span class="muted small">{s.use}</span></li>
					{/each}
				</ul>
			</section>

			<section id="motion">
				<h2>{ut('sg.section.motion')}</h2>
				<p class="lead">{ut('sg.motion.lead')}</p>
				<div class="row"><Button variant="outline" icon="review" onclick={() => (played = !played)}>{ut('sg.motion.play')}</Button></div>
				<ul class="motion-list">
					{#each tokens.duration as d (d.name)}
						<li>
							<code>--kb-duration-{d.name}</code><span class="muted small">{d.ms} ms</span>
							<span class="track"><span class="ball" class:played style:transition-duration="{d.ms}ms"></span></span>
						</li>
					{/each}
					{#each tokens.easing as e (e.name)}
						<li>
							<code>--kb-ease-{e.name}</code><span class="muted small">{e.css}</span>
							<span class="track"><span class="ball" class:played style:transition-timing-function={e.css}></span></span>
						</li>
					{/each}
				</ul>
			</section>

			<section id="components">
				<h2>{ut('sg.section.components')}</h2>
				<div class="themes">
					<div class="theme-panel" data-theme="light">
						<p class="panel-label"><span class="eyebrow">{ut('ui.theme.light')}</span></p>
						{@render gallery()}
					</div>
					<div class="theme-panel" data-theme="dark">
						<p class="panel-label"><span class="eyebrow">{ut('ui.theme.dark')}</span></p>
						{@render gallery()}
					</div>
				</div>
			</section>

			<section id="review">
				<h2>{ut('sg.section.review')}</h2>
				<div class="phones">
					{#each ['light', 'dark'] as t (t)}
						{#each [false, true] as back (back)}
							<figure class="phone">
								<div data-theme={t}>{@render reviewScreen(back)}</div>
								<figcaption>{t === 'light' ? ut('ui.theme.light') : ut('ui.theme.dark')} · {back ? ut('sg.demo.back') : ut('sg.demo.front')}</figcaption>
							</figure>
						{/each}
					{/each}
				</div>
			</section>

			<section id="shell">
				<h2>{ut('sg.section.shell')}</h2>
				{#each [{ k: 'sg.demo.shellWide', w: '100%' }, { k: 'sg.demo.shellMedium', w: '760px' }, { k: 'sg.demo.shellCompact', w: '360px' }] as f (f.k)}
					<figure class="frame-fig">
						<figcaption class="muted small">{ut(f.k as UiMessageKey)}</figcaption>
						<div class="frame" style:width="min({f.w}, 100%)">
							<AppShell items={navItems} current="/review" brand={ut('sg.brand')}>
								{#snippet actions()}<IconButton icon="sync" label={ut('sg.demo.loading')} />{/snippet}
								<div class="frame-page">
									<h3>{ut('sg.nav.review')}</h3>
									<Surface>
										<p class="big">85</p>
										<p class="muted">{ut('sg.demo.shellContent')}</p>
									</Surface>
								</div>
							</AppShell>
						</div>
					</figure>
				{/each}
			</section>
		</div>
	</div>
</div>

<Dialog bind:open={dialogOpen} title={ut('sg.demo.dialogTitle')}>
	{ut('sg.demo.dialogBody')}
	{#snippet actions()}
		<Button variant="quiet" onclick={() => (dialogOpen = false)}>{ut('sg.demo.cancel')}</Button>
		<Button variant="danger" onclick={() => (dialogOpen = false)}>{ut('sg.demo.delete')}</Button>
	{/snippet}
</Dialog>

<style>
	/* Until UI-WEB replaces the POC layout, the styleguide hides its header and width cap. */
	:global(header.bar:has(~ main .sg)) {
		display: none;
	}
	:global(main:has(> .sg)) {
		max-width: none;
		padding: 0;
	}

	.sg {
		min-height: 100dvh;
		background: var(--kb-color-bg);
		color: var(--kb-color-text);
		font: var(--kb-type-body);
	}
	h1,
	h2,
	h3,
	p {
		margin: 0;
	}
	code {
		font: 0.8125rem/1.4 var(--kb-family-mono);
		color: var(--kb-color-text);
		overflow-wrap: anywhere;
	}
	.eyebrow {
		font: var(--kb-type-overline);
		letter-spacing: var(--kb-type-overline-tracking);
		text-transform: uppercase;
		color: var(--kb-color-text-muted);
	}
	.lead {
		max-width: 46rem;
		font: var(--kb-type-body-lg);
		color: var(--kb-color-text-muted);
	}
	.muted {
		color: var(--kb-color-text-muted);
	}
	.small {
		font: var(--kb-type-body-sm);
	}
	.big {
		font: var(--kb-type-display);
		letter-spacing: var(--kb-type-display-tracking);
		font-variant-numeric: tabular-nums;
	}

	.hero {
		border-bottom: 1px solid var(--kb-color-border);
		background: var(--kb-color-surface);
	}
	.hero-inner {
		display: flex;
		flex-wrap: wrap;
		align-items: flex-start;
		gap: var(--kb-space-5);
		max-width: 90rem;
		margin: 0 auto;
		padding: clamp(var(--kb-space-6), 5vw, var(--kb-space-16)) clamp(var(--kb-space-4), 4vw, var(--kb-space-12));
	}
	.seal {
		display: grid;
		place-items: center;
		width: 3.5rem;
		height: 3.5rem;
		flex: none;
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-brand);
		color: var(--kb-color-surface);
		font: 600 2rem/1 var(--kb-family-serif);
	}
	.hero-text {
		flex: 1 1 24rem;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		min-width: 0;
	}
	h1 {
		font: var(--kb-type-display);
		letter-spacing: var(--kb-type-display-tracking);
	}
	.hero-theme {
		flex: 0 1 auto;
		min-width: 0;
		max-width: 100%;
	}

	.layout {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: var(--kb-space-8);
		max-width: 90rem;
		margin: 0 auto;
		padding: var(--kb-space-8) clamp(var(--kb-space-4), 4vw, var(--kb-space-12)) var(--kb-space-24);
	}
	.toc {
		display: none;
	}
	@media (min-width: 1200px) {
		.layout {
			grid-template-columns: 12rem minmax(0, 1fr);
		}
		.toc {
			display: block;
			position: sticky;
			top: var(--kb-space-8);
			align-self: start;
		}
		.toc ul {
			list-style: none;
			margin: var(--kb-space-3) 0 0;
			padding: 0;
			display: flex;
			flex-direction: column;
			gap: var(--kb-space-1);
		}
		.toc a {
			display: block;
			padding: var(--kb-space-1) var(--kb-space-3);
			border-radius: var(--kb-radius-sm);
			color: var(--kb-color-text-muted);
			text-decoration: none;
			font: var(--kb-type-label);
		}
		.toc a:hover {
			background: var(--kb-color-surface-hover);
			color: var(--kb-color-text);
		}
	}
	.sections {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-16);
		min-width: 0;
	}
	section {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		scroll-margin-top: var(--kb-space-6);
	}
	h2 {
		font: var(--kb-type-headline);
		letter-spacing: var(--kb-type-headline-tracking);
		padding-bottom: var(--kb-space-2);
		border-bottom: 1px solid var(--kb-color-border);
	}
	h3 {
		font: var(--kb-type-title-sm);
		margin-top: var(--kb-space-2);
	}

	/* colour */
	.swatches {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 17rem), 1fr));
		gap: var(--kb-space-3);
	}
	.swatch {
		display: flex;
		gap: var(--kb-space-3);
		padding: var(--kb-space-3);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface);
		min-width: 0;
	}
	.chips {
		display: flex;
		flex: none;
		width: 3.5rem;
		height: 3.5rem;
		border-radius: var(--kb-radius-sm);
		overflow: hidden;
		box-shadow: inset 0 0 0 1px var(--kb-color-border);
	}
	.chips span {
		flex: 1;
		box-shadow: inset 0 0 0 1px rgb(127 127 127 / 0.15);
	}
	.sw-text {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.hexes {
		font: 0.75rem/1.4 var(--kb-family-mono);
		color: var(--kb-color-text-muted);
	}
	.use {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.ratio {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-2);
		font-variant-numeric: tabular-nums;
	}
	.sample {
		display: inline-block;
		padding: 0 var(--kb-space-1);
		border-radius: var(--kb-radius-xs);
		font: 600 0.75rem/1.5 var(--kb-family-sans);
		box-shadow: inset 0 0 0 1px rgb(127 127 127 / 0.25);
	}

	/* type */
	.type-list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
	}
	.type-list li {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: var(--kb-space-2);
		padding: var(--kb-space-4) 0;
		border-bottom: 1px solid var(--kb-color-border);
	}
	@media (min-width: 840px) {
		.type-list li {
			grid-template-columns: 16rem minmax(0, 1fr);
			gap: var(--kb-space-6);
			align-items: start;
		}
	}
	.type-meta {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.specimen {
		overflow-wrap: anywhere;
		word-break: auto-phrase;
	}
	.upper {
		text-transform: uppercase;
	}
	.ruby-demo {
		font: var(--kb-type-jp-display);
		margin: var(--kb-space-4) 0;
	}
	.ruby-demo ruby,
	.ruby-lines ruby {
		ruby-align: center;
	}
	.ruby-demo rt,
	.ruby-lines rt {
		font-size: max(calc(var(--kb-ruby-scale) * 1em), var(--kb-ruby-min));
		color: var(--kb-color-text-muted);
	}
	.jp-body {
		font: var(--kb-type-jp-body);
		letter-spacing: var(--kb-type-jp-body-tracking);
		margin: var(--kb-space-3) 0;
		word-break: auto-phrase;
	}
	.ruby-lines {
		line-height: var(--kb-ruby-lh);
	}

	/* space */
	.space-list,
	.size-list,
	.motion-list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
	}
	.space-list li {
		display: grid;
		grid-template-columns: minmax(0, 9rem) minmax(0, 1fr) auto;
		align-items: center;
		gap: var(--kb-space-3);
	}
	.space-list .bar {
		max-width: 100%;
		height: 0.75rem;
		border-radius: 2px;
		background: var(--kb-color-primary);
		min-width: 1px;
	}
	.radius-item {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--kb-space-1);
	}
	.radius {
		width: 4.5rem;
		height: 4.5rem;
		background: var(--kb-color-primary-container);
		border: 1.5px solid var(--kb-color-primary);
	}
	.elev {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		justify-content: flex-end;
		width: 11rem;
		height: 6.5rem;
		padding: var(--kb-space-3);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface-raised);
		border: 1px solid var(--kb-color-border);
	}
	.size-list li {
		display: grid;
		grid-template-columns: minmax(0, 12rem) 8rem minmax(0, 1fr);
		gap: var(--kb-space-3);
		align-items: baseline;
	}
	@media (max-width: 599.98px) {
		.size-list li {
			grid-template-columns: minmax(0, 1fr) auto;
		}
		.size-list li .small {
			grid-column: 1 / -1;
		}
	}

	/* motion */
	.motion-list li {
		display: grid;
		grid-template-columns: minmax(0, 13rem) minmax(0, 13rem) minmax(0, 1fr);
		gap: var(--kb-space-3);
		align-items: center;
	}
	@media (max-width: 839.98px) {
		.motion-list li {
			grid-template-columns: minmax(0, 1fr) auto;
		}
		.motion-list .track {
			grid-column: 1 / -1;
		}
	}
	.track {
		position: relative;
		height: 1.25rem;
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
	}
	.ball {
		position: absolute;
		top: 0.125rem;
		left: 0.125rem;
		width: 1rem;
		height: 1rem;
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-primary);
		transition-property: left;
		transition-duration: var(--kb-duration-slow);
		transition-timing-function: var(--kb-ease-standard);
	}
	.ball.played {
		left: calc(100% - 1.125rem);
	}

	/* components in both themes */
	.themes {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: var(--kb-space-4);
	}
	@media (min-width: 1500px) {
		.themes {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}
	.theme-panel {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-8);
		padding: clamp(var(--kb-space-4), 3vw, var(--kb-space-8));
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-xl);
		background: var(--kb-color-bg);
		color: var(--kb-color-text);
		min-width: 0;
	}
	.panel-label {
		margin-bottom: calc(-1 * var(--kb-space-4));
	}
	.block {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
		min-width: 0;
	}
	.block h3 {
		margin: 0;
	}
	.row {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--kb-space-2);
	}
	.row.wide {
		gap: var(--kb-space-6);
	}
	.stack {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
	}
	.grid2 {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 16rem), 1fr));
		gap: var(--kb-space-3);
	}
	.pad {
		padding: var(--kb-space-3) var(--kb-space-4);
	}
	.deck-dot {
		display: inline-block;
		width: 0.75rem;
		height: 0.75rem;
		flex: none;
		border-radius: var(--kb-radius-full);
	}
	.deck-cell {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-2);
		overflow: hidden;
		text-overflow: ellipsis;
	}

	/* review composition */
	.phones {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 20rem), 1fr));
		gap: var(--kb-space-5);
	}
	.phone {
		margin: 0;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
	}
	.phone figcaption {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		text-align: center;
	}
	.review {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		height: max(40rem, 70vh);
		padding: var(--kb-space-4);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-xl);
		background: var(--kb-color-bg);
		color: var(--kb-color-text);
	}
	.review-top {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
	}
	.review-deck {
		display: flex;
	}
	.review-card {
		flex: 1;
		display: flex;
		flex-direction: column;
		justify-content: safe center;
		min-height: 0;
		overflow-y: auto;
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
		box-shadow: var(--kb-elevation-1);
	}
	.review-actions {
		display: flex;
	}

	/* app shell frames */
	.frame-fig {
		margin: 0;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
	}
	.frame {
		--kb-shell-height: 36rem;
		height: 36rem;
		overflow: auto;
		border: 1px solid var(--kb-color-border-strong);
		border-radius: var(--kb-radius-lg);
	}
	.frame-page {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		padding: var(--kb-space-6);
		max-width: 48rem;
	}
	.frame-page h3 {
		font: var(--kb-type-headline);
	}
</style>
