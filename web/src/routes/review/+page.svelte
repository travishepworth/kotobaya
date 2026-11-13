<script lang="ts">
	// Review: the product. A full-height card, the answer reveal, and the grade bar in the thumb zone.
	// Keyboard-first on desktop (Space, 1-4/5, U, E, ?), one-handed on phones.
	import { tick } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import Banner from '../../lib/ui/Banner.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import CardFrame from '../../lib/ui/CardFrame.svelte';
	import Dialog from '../../lib/ui/Dialog.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import LoadError from '../../lib/ui/LoadError.svelte';
	import GradeBar from '../../lib/ui/GradeBar.svelte';
	import IconButton from '../../lib/ui/IconButton.svelte';
	import Kbd from '../../lib/ui/Kbd.svelte';
	import Menu from '../../lib/ui/Menu.svelte';
	import ProgressBar from '../../lib/ui/ProgressBar.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import type { Grade, GradeOption, MenuItem } from '../../lib/ui/kinds';
	import { tr } from '../../lib/review/messages';
	import { ReviewPage, type GradeButton } from '../../lib/state/review.svelte';
	import EditNoteDialog from './EditNoteDialog.svelte';
	import { rv, type ReviewViewKey } from './messages';

	const s = new ReviewPage();
	let frame: CardFrame | undefined = $state();
	let helpOpen = $state(false);
	let body: HTMLDivElement | undefined = $state();
	// True while part of the card is below the fold: a fade at the bottom says the card scrolls.
	let moreBelow = $state(false);

	const drillSource = $derived(page.url.searchParams.get('drill'));

	// (Re)load whenever the drill source changes; start() reads no state, so this runs once per source.
	$effect(() => {
		s.start(drillSource);
	});

	/* ---------- answer in view ---------- */

	function measure(): void {
		if (!body) return;
		moreBelow = body.scrollTop + body.clientHeight < body.scrollHeight - 2;
	}

	// Large text or a short window can push the answer below the fold. On flip, scroll just far
	// enough to show the answer (never past the divider), so nobody grades without seeing it.
	function bringAnswerIntoView(el: HTMLDivElement): void {
		const divider = el.querySelector('.kb-divider');
		const overflow = el.scrollHeight - el.clientHeight;
		if (!divider || overflow <= 0) return;
		const pad = 16; // --kb-space-4
		const dividerTop = divider.getBoundingClientRect().top - el.getBoundingClientRect().top + el.scrollTop - pad;
		const top = Math.max(0, Math.min(dividerTop, overflow));
		if (top <= el.scrollTop) return;
		const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
		el.scrollTo({ top, behavior: reduce ? 'auto' : 'smooth' });
	}

	$effect(() => {
		const flipped = s.flipped;
		void s.rendered;
		const el = body;
		if (!el) return;
		void tick().then(() => {
			if (flipped) bringAnswerIntoView(el);
			measure();
		});
		if (typeof ResizeObserver === 'undefined') return;
		const ro = new ResizeObserver(measure);
		ro.observe(el);
		for (const child of el.children) ro.observe(child);
		return () => ro.disconnect();
	});

	/* ---------- header figures ---------- */

	const remaining = $derived(
		s.isDrill || !s.counts ? s.items.length : s.counts._new + s.counts.learning + s.counts.review
	);
	const done = $derived(s.history.length);
	const total = $derived(done + remaining);
	// A heavy day (e.g. after a break): a calm tip instead of a wall of numbers.
	const bigDay = $derived(!s.isDrill && remaining >= 200);

	const DRILL_NAMES: Record<string, ReviewViewKey> = {
		leeches: 'rv.drill.leeches',
		confusables: 'rv.drill.confusables',
		hot_words: 'rv.drill.hot'
	};
	const modeLabel = $derived(
		s.drillSource !== null
			? rv('rv.mode.drill', { source: DRILL_NAMES[s.drillSource] ? rv(DRILL_NAMES[s.drillSource]) : s.drillSource })
			: rv('rv.mode.today')
	);
	const leaveHref = $derived(s.isDrill ? '/struggle' : '/');
	const leaveLabel = $derived(s.isDrill ? rv('rv.leaveDrill') : rv('rv.leave'));

	/* ---------- grades ---------- */

	const TONE: Record<GradeButton['label'], Grade> = {
		again: 'again',
		nothing: 'again',
		something: 'again',
		hard: 'hard',
		good: 'good',
		okay: 'good',
		easy: 'easy'
	};
	const gradeOptions = $derived<GradeOption[]>(
		s.buttons.map((b) => ({
			id: b.label,
			label: tr(`review.grade.${b.label}`),
			tone: TONE[b.label],
			// Drills never reschedule: no interval to preview.
			interval: s.isDrill ? undefined : s.previewText(b) || undefined
		}))
	);

	function gradeAt(i: number): void {
		const b = s.buttons[i];
		if (b) void s.grade(b);
	}

	/* ---------- menu ---------- */

	const menuItems = $derived<MenuItem[]>([
		{ label: rv('rv.undo'), icon: 'undo', key: 'U', disabled: s.history.length === 0, onselect: () => void s.undo() },
		{ label: rv('rv.edit'), icon: 'edit', key: 'E', disabled: !s.current, onselect: () => s.startEdit() },
		{ label: rv('rv.play'), icon: 'audio', key: 'P', disabled: !s.current, onselect: () => frame?.playFirst() },
		{ label: rv('rv.furigana'), key: 'F', checked: s.furigana, onselect: () => s.toggleFurigana() },
		{ label: rv('rv.bury'), icon: 'clock', key: 'B', disabled: !s.current, onselect: () => void s.bury() },
		{ label: rv('rv.suspend'), icon: 'pause', key: 'S', disabled: !s.current, onselect: () => void s.suspend() },
		{ label: rv('rv.neverForget'), icon: 'sparkle', key: 'N', disabled: !s.current, onselect: () => void s.neverForget() },
		{ label: rv('rv.shortcuts'), icon: 'info', key: '?', onselect: () => (helpOpen = true) }
	]);

	/* ---------- keyboard ---------- */

	function onKeydown(e: KeyboardEvent): void {
		if (e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented) return;
		const el = e.target as HTMLElement | null;
		const tag = el?.tagName;
		if (s.editing || helpOpen || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el?.isContentEditable) return;
		if (el?.closest('[role="menu"]')) return;
		const k = e.key;
		if (k === '?') {
			e.preventDefault();
			helpOpen = true;
			return;
		}
		if (k === 'Escape') {
			void goto(leaveHref);
			return;
		}
		if (!s.current) return;
		if (k === ' ' || k === 'Enter') {
			// A focused button handles its own activation.
			if (tag === 'BUTTON' || tag === 'A') return;
			e.preventDefault();
			if (!s.flipped) s.flip();
			return;
		}
		if (/^[1-5]$/.test(k)) {
			if (s.flipped && Number(k) <= s.buttons.length) {
				e.preventDefault();
				gradeAt(Number(k) - 1);
			}
			return;
		}
		switch (k.toLowerCase()) {
			case 'u':
				void s.undo();
				break;
			case 'e':
				e.preventDefault();
				s.startEdit();
				break;
			case 'b':
				void s.bury();
				break;
			case 's':
				void s.suspend();
				break;
			case 'n':
				void s.neverForget();
				break;
			case 'f':
				s.toggleFurigana();
				break;
			case 'p':
				frame?.playFirst();
				break;
		}
	}

	function timeOf(d: Date): string {
		return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
	}

	const SHORTCUTS = $derived<[string, string][]>([
		['Space', rv('rv.key.space')],
		[s.gradeKeys.replace('-', '–'), rv('rv.key.grades', { keys: s.buttons.map((b) => tr(`review.grade.${b.label}`)).join(', ') })],
		['U', rv('rv.key.undo')],
		['E', rv('rv.key.edit')],
		['P', rv('rv.key.play')],
		['F', rv('rv.key.furigana')],
		['B', rv('rv.key.bury')],
		['S', rv('rv.key.suspend')],
		['N', rv('rv.key.never')],
		['?', rv('rv.key.help')],
		['Esc', rv('rv.key.leave')]
	]);
</script>

<svelte:window onkeydown={onKeydown} />
<svelte:head><title>{rv('rv.title')} · Kotobaya</title></svelte:head>

<div class="review">
	<header class="bar">
		<IconButton icon="close" href={leaveHref} label={leaveLabel} />
		<div class="status">
			<div class="line">
				<h1 class="mode">{modeLabel}</h1>
				{#if s.loaded && s.counts && !s.isDrill && remaining > 0}
					<p class="counts">
						<span class="count new"><i aria-hidden="true"></i>{rv('rv.count.new', { n: s.counts._new })}</span>
						<span class="count learning"><i aria-hidden="true"></i>{rv('rv.count.learning', { n: s.counts.learning })}</span>
						<span class="count due"><i aria-hidden="true"></i>{rv('rv.count.review', { n: s.counts.review })}</span>
					</p>
				{/if}
				{#if s.loaded && total > 0}
					<p class="of">{rv('rv.of', { done, total })}</p>
				{/if}
			</div>
			{#if !s.loaded || total > 0}
				<ProgressBar value={done} max={Math.max(total, 1)} label={rv('rv.progress')} />
			{/if}
		</div>
		<Menu label={rv('rv.menu')} items={menuItems} />
	</header>

	{#if bigDay && s.current}
		<div class="notice">
			<Banner title={rv('rv.big.title', { n: remaining })}>{rv('rv.big.body')}</Banner>
		</div>
	{/if}

	<section class="stage" aria-label={rv('rv.title')}>
		{#if !s.loaded}
			<div class="card" aria-busy="true">
				<div class="card-body skeleton">
					<span class="kb-sr">{rv('rv.loading')}</span>
					<Skeleton shape="block" height="4.5rem" />
					<Skeleton shape="text" count={2} />
				</div>
			</div>
		{:else if s.loadFailed}
			<div class="finished">
				<LoadError size="page" title={rv('rv.loadFailed')} onretry={() => s.retry()} />
			</div>
		{:else if s.drillUnavailable}
			<div class="finished">
				<EmptyState size="page" art="info" title={rv('rv.drillUnavailable.title')} body={rv('rv.drillUnavailable.body')}>
					{#snippet action()}<Button variant="primary" href="/struggle">{rv('rv.leaveDrill')}</Button>{/snippet}
				</EmptyState>
			</div>
		{:else if !s.current}
			<div class="finished" aria-live="polite">
				{#if s.isDrill}
					<EmptyState size="page" art="enso" title={rv('rv.drillDone.title')} body={rv('rv.drillDone.body')}>
						{#snippet action()}
							<div class="done-actions">
								<Button variant="primary" href="/struggle">{rv('rv.leaveDrill')}</Button>
								{#if s.history.length > 0}
									<Button variant="quiet" icon="undo" onclick={() => s.undo()}>{rv('rv.undo')}</Button>
								{/if}
							</div>
						{/snippet}
					</EmptyState>
				{:else}
					<EmptyState
						size="page"
						art="enso"
						title={rv('rv.done.title')}
						body={s.dayEndsAt ? rv('rv.done.next', { time: timeOf(s.dayEndsAt) }) : rv('rv.done.body')}
					>
						{#snippet action()}
							<div class="done-actions">
								<Button variant="primary" href="/">{rv('rv.done.today')}</Button>
								<Button variant="outline" icon="sync" loading={s.busy} onclick={() => s.reload()}>{rv('rv.done.check')}</Button>
								{#if s.history.length > 0}
									<Button variant="quiet" icon="undo" onclick={() => s.undo()}>{rv('rv.undo')}</Button>
								{/if}
								<a class="quiet-link" href="/struggle/leeches">{rv('rv.done.leeches')}</a>
							</div>
						{/snippet}
					</EmptyState>
				{/if}
			</div>
		{:else}
			{#key s.current.card.id}
				<article class="card enter" class:flipped={s.flipped} class:more-below={moreBelow} aria-live="polite">
					<div class="card-body" bind:this={body} onscroll={measure}>
						{#if s.reviewTypeKey}<p class="tag">{tr(s.reviewTypeKey)}</p>{/if}
						{#if s.rendered === null}
							<!-- unreachable: current is set -->
						{:else if s.rendered.ok}
							<CardFrame bind:this={frame} rendered={s.rendered.card} furigana={s.furigana} reveal={s.flipped} />
							{#if s.noExample && s.flipped}
								<p class="note">{tr('review.noExampleSentence')}</p>
							{/if}
						{:else}
							<EmptyState tone="error" art="alert" title={rv('rv.renderFailed')} body={s.rendered.detail} />
						{/if}
					</div>
				</article>
			{/key}
		{/if}
	</section>

	<footer class="actions">
		{#if !s.loaded || s.current}
			<div class="slot">
				{#if !s.flipped || !s.current}
					<div class="swap">
						<Button variant="primary" size="lg" full disabled={!s.current} onclick={() => s.flip()} aria-keyshortcuts="Space">
							{rv('rv.showAnswer')}
						</Button>
					</div>
				{:else}
					<div class="swap">
						<GradeBar options={gradeOptions} ongrade={(_, i) => gradeAt(i)} />
					</div>
				{/if}
			</div>
			<p class="hints" aria-hidden="true">
				{#if !s.flipped}
					<span><Kbd keys="Space" /> {rv('rv.hint.show')}</span>
				{:else}
					<span><Kbd keys={s.gradeKeys.replace('-', '–')} /> {rv('rv.hint.grade')}</span>
				{/if}
				<span><Kbd keys="U" /> {rv('rv.hint.undo')}</span>
				<span><Kbd keys="?" /> {rv('rv.hint.more')}</span>
			</p>
		{/if}
	</footer>
</div>

{#if s.editing && s.current?.card.note}
	<EditNoteDialog
		note={s.current.card.note}
		noteType={s.noteTypeOf(s.current.card.note)}
		onsaved={(n) => s.noteSaved(n)}
		oncancel={() => s.cancelEdit()}
	/>
{/if}

<Dialog bind:open={helpOpen} title={rv('rv.sheet.title')}>
	<dl class="keys">
		{#each SHORTCUTS as [key, what] (key)}
			<div class="key-row"><dt><Kbd keys={key} /></dt><dd>{what}</dd></div>
		{/each}
	</dl>
</Dialog>

<style>
	.review {
		--gutter: var(--kb-space-3);
		display: flex;
		flex-direction: column;
		min-height: 100dvh;
		height: 100dvh;
		background: var(--kb-color-bg);
	}

	/* ---------- header ---------- */
	.bar {
		display: flex;
		align-items: center;
		gap: var(--kb-space-2);
		width: 100%;
		max-width: calc(var(--kb-size-review-card) + 2 * var(--gutter) + 6rem);
		margin: 0 auto;
		min-height: var(--kb-size-top-bar);
		padding: var(--kb-space-1) var(--kb-space-1);
	}
	.status {
		container-type: inline-size;
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		padding: var(--kb-space-1) var(--kb-space-1) 0;
	}
	.line {
		display: flex;
		align-items: baseline;
		gap: var(--kb-space-3);
		min-width: 0;
	}
	.mode {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font: var(--kb-type-label);
		color: var(--kb-color-text);
	}
	.of {
		flex: none;
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		font-variant-numeric: tabular-nums;
	}
	.counts {
		display: flex;
		flex-wrap: wrap;
		gap: 0 var(--kb-space-3);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		font-variant-numeric: tabular-nums;
	}
	.count {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-1);
		white-space: nowrap;
	}
	.count i {
		width: 0.5rem;
		height: 0.5rem;
		border-radius: 50%;
		background: var(--kb-color-state-new);
	}
	.count.learning i {
		background: var(--kb-color-state-learning);
	}
	.count.due i {
		background: var(--kb-color-state-review);
	}
	/* Narrow header (phones, large text): the three counts are the first thing to go; the progress count stays. */
	@container (max-width: 34rem) {
		.counts {
			display: none;
		}
	}
	/* Very narrow (phones at 200 % text): the bar and its count say enough. */
	@container (max-width: 16rem) {
		.mode {
			position: absolute;
			width: 1px;
			height: 1px;
			overflow: hidden;
			clip-path: inset(50%);
		}
		.line {
			justify-content: flex-end;
		}
	}

	.notice {
		width: 100%;
		max-width: calc(var(--kb-size-review-card) + 2 * var(--gutter));
		margin: 0 auto;
		padding: 0 var(--gutter) var(--kb-space-2);
	}

	/* ---------- card ---------- */
	.stage {
		flex: 1 1 auto;
		min-height: 0;
		display: flex;
		flex-direction: column;
		width: 100%;
		max-width: calc(var(--kb-size-review-card) + 2 * var(--gutter));
		margin: 0 auto;
		padding: 0 var(--gutter);
	}
	.card {
		position: relative;
		flex: 1 1 auto;
		min-height: 12rem;
		display: flex;
		flex-direction: column;
		overflow: hidden;
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface);
		box-shadow: var(--kb-elevation-1);
		/* Card-relative units (cqh) let the revealed card fit its own height, whatever the text size. */
		container-type: size;
	}
	/* More below: a fade over the bottom edge says the card scrolls. */
	.card::after {
		content: '';
		position: absolute;
		inset: auto 0 0;
		height: var(--kb-space-12);
		border-radius: 0 0 var(--kb-radius-lg) var(--kb-radius-lg);
		background: linear-gradient(to bottom, transparent, var(--kb-color-surface));
		pointer-events: none;
		opacity: 0;
		transition: opacity var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.card.more-below::after {
		opacity: 1;
	}
	.card-body {
		flex: 1;
		overflow-y: auto;
		overscroll-behavior: contain;
		/* Top-anchored at the optical centre: the front sits where it was when the answer appears below it. */
		/* Viewport-relative (not rem) so large text does not push the answer below the fold. */
		padding: clamp(24px, 12dvh, 112px) clamp(var(--kb-space-4), 5vw, var(--kb-space-12))
			var(--kb-space-10);
	}
	.skeleton {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--kb-space-6);
	}
	.skeleton > :global(*) {
		width: min(20rem, 80%);
	}
	.enter {
		animation: card-in var(--kb-duration-base) var(--kb-ease-decelerate);
	}
	@keyframes card-in {
		from {
			opacity: 0;
			transform: translateX(var(--kb-distance-lg));
		}
	}
	.tag {
		position: absolute;
		top: var(--kb-space-3);
		left: var(--kb-space-4);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.note {
		max-width: 32rem;
		margin: var(--kb-space-6) auto 0;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		text-align: center;
	}
	/* Review is the one place the headword gets the most room. */
	.card-body :global(.kb-expression) {
		font-size: clamp(3rem, 2.25rem + 3.2vw, 5rem);
	}
	.card-body :global(.kb-literal) {
		font-size: clamp(4.5rem, 3.4rem + 4.5vw, 7rem);
	}
	/* Revealed: the question steps back so the answer fits. The top padding and the headword are
	   capped by the card's own height, so at 200 % text the answer stays on screen. */
	.flipped .card-body {
		padding-top: min(clamp(24px, 12dvh, 112px), 8cqh);
	}
	.flipped .card-body :global(.kb-expression) {
		font-size: min(clamp(3rem, 2.25rem + 3.2vw, 5rem), max(2rem, 13cqh));
	}
	.flipped .card-body :global(.kb-literal) {
		font-size: min(clamp(4.5rem, 3.4rem + 4.5vw, 7rem), max(2.5rem, 18cqh));
	}
	.flipped .card-body :global(.kb-divider) {
		margin: min(1rem, 3cqh) 0;
	}

	/* ---------- finished ---------- */
	.finished {
		flex: 1;
		display: grid;
		place-items: center;
		padding: var(--kb-space-8) 0;
	}
	.finished :global(.enso) {
		color: var(--kb-color-brand);
	}
	.done-actions {
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

	/* ---------- actions (thumb zone) ---------- */
	.actions {
		width: 100%;
		max-width: calc(var(--kb-size-review-card) + 2 * var(--gutter));
		margin: 0 auto;
		padding: var(--kb-space-3) var(--gutter) calc(var(--kb-space-3) + env(safe-area-inset-bottom));
	}
	.slot {
		display: grid;
		min-height: var(--kb-size-grade-button);
	}
	.swap {
		display: grid;
		align-content: end;
		animation: swap var(--kb-duration-base) var(--kb-ease-standard);
	}
	.swap :global(.btn.lg) {
		min-height: var(--kb-size-grade-button);
	}
	@keyframes swap {
		from {
			opacity: 0;
		}
	}
	.hints {
		display: none;
		justify-content: center;
		flex-wrap: wrap;
		gap: var(--kb-space-1) var(--kb-space-5);
		margin-top: var(--kb-space-3);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.hints span {
		display: inline-flex;
		align-items: center;
		gap: var(--kb-space-2);
	}
	/* Key hints: keyboard devices with room for them. */
	@media (hover: hover) and (pointer: fine) and (min-width: 600px) {
		.hints {
			display: flex;
		}
	}

	/* Tablet and up: a little more air around the card. */
	@media (min-width: 600px) {
		.review {
			--gutter: var(--kb-space-6);
		}
		.bar {
			padding: var(--kb-space-2) var(--kb-space-4);
		}
	}
	/* Desktop: the card and its actions form one centred block; actions sit right under the card. */
	@media (min-width: 1024px) and (min-height: 640px) {
		.review {
			justify-content: flex-start;
		}
		.stage {
			/* Fills the room left, up to 36 rem; actions sit right under it (large text keeps the answer in view). */
			flex: 1 1 auto;
			max-height: 36rem;
			margin-top: clamp(var(--kb-space-2), 4dvh, var(--kb-space-10));
		}
		.actions {
			padding-top: var(--kb-space-5);
		}
	}

	/* ---------- shortcuts sheet ---------- */
	.keys {
		display: flex;
		flex-direction: column;
		margin: 0;
		color: var(--kb-color-text);
	}
	.key-row {
		display: grid;
		grid-template-columns: 5.5rem 1fr;
		align-items: center;
		gap: var(--kb-space-3);
		min-height: 2.5rem;
		border-bottom: 1px solid var(--kb-color-border);
	}
	.key-row:last-child {
		border-bottom: 0;
	}
	dd {
		margin: 0;
		font: var(--kb-type-body-sm);
	}
</style>
