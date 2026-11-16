<script lang="ts">
	// Review: the product. A full-height card, the answer reveal, and the grade bar in the thumb zone.
	// Keyboard-first on desktop (Space, 1-4/5, U, E, ?), one-handed on phones.
	import { onDestroy, tick, untrack } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { registerReviewBack } from '../../lib/ui/backStack.svelte';
	import Banner from '../../lib/ui/Banner.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import CardFrame from '../../lib/ui/CardFrame.svelte';
	import Dialog from '../../lib/ui/Dialog.svelte';
	import DictionaryLink from '../../lib/ui/DictionaryLink.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import LoadError from '../../lib/ui/LoadError.svelte';
	import GradeBar from '../../lib/ui/GradeBar.svelte';
	import Icon from '../../lib/ui/Icon.svelte';
	import IconButton from '../../lib/ui/IconButton.svelte';
	import Kbd from '../../lib/ui/Kbd.svelte';
	import Menu from '../../lib/ui/Menu.svelte';
	import ProgressBar from '../../lib/ui/ProgressBar.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import type { Grade, GradeOption, MenuItem } from '../../lib/ui/kinds';
	import { tr } from '../../lib/review/messages';
	import { ReviewPage, type GradeButton } from '../../lib/state/review.svelte';
	import BackExtras from './BackExtras.svelte';
	import EditNoteDialog from './EditNoteDialog.svelte';
	import LoopEnd from './LoopEnd.svelte';
	import { rv, type ReviewViewKey } from './messages';

	const s = new ReviewPage();
	let frame: CardFrame | undefined = $state();
	let helpOpen = $state(false);
	let body: HTMLDivElement | undefined = $state();
	// True while part of the card is below the fold: a fade at the bottom says the card scrolls.
	let moreBelow = $state(false);

	// Toasts sit in the free band just above the answer/grade bar: publish its measured height (it already
	// includes the safe-area padding) for ToastHost, so a toast never covers Show answer / grades / the top bar.
	let actionsEl = $state<HTMLElement>();
	$effect(() => {
		const el = actionsEl;
		if (!el) return;
		const root = document.documentElement;
		const publish = (): void => root.style.setProperty('--kb-toast-lift', `${el.offsetHeight}px`);
		publish();
		const ro = new ResizeObserver(publish);
		ro.observe(el);
		return () => {
			ro.disconnect();
			root.style.removeProperty('--kb-toast-lift');
		};
	});
	onDestroy(() => s.dispose());
	// SHELL.md 6.2 step 2: the shell's Back undoes the last answer once (ADR-030).
	$effect(() => registerReviewBack({ canUndo: () => s.canBackUndo(), undo: () => s.backUndo() }));

	// RC-WEB: a load that takes long says so and offers a retry, instead of a skeleton that never ends.
	const SLOW_MS = 5000;
	let slow = $state(false);
	$effect(() => {
		if (s.phase !== 'loading' && s.phase !== 'settling') {
			slow = false;
			return;
		}
		const t = setTimeout(() => (slow = true), SLOW_MS);
		return () => clearTimeout(t);
	});

	const drillSource = $derived(page.url.searchParams.get('drill'));

	// (Re)load whenever the drill source changes; start() reads no state, so this runs once per source.
	$effect(() => {
		const source = drillSource;
		// untrack: start() reads page state while it fetches; a tap on "Keep going" must not restart the session.
		untrack(() => s.start(source));
	});

	/* ---------- answer in view ---------- */

	function measure(): void {
		if (!body) return;
		moreBelow = body.scrollTop + body.clientHeight < body.scrollHeight - 2;
	}

	// Large text or a short window can push the answer below the fold. On flip, scroll just far
	// enough to show the answer (never past the divider), so nobody grades without seeing it.
	async function bringAnswerIntoView(el: HTMLDivElement): Promise<void> {
		const divider = el.querySelector('.kb-divider');
		const overflow = el.scrollHeight - el.clientHeight;
		if (!divider || overflow <= 0) return;
		// Align the bottom edge of the question (the element right above the divider) with the container's top
		// edge, so the cut falls in the whitespace between question and divider, never through a glyph row.
		const question = divider.previousElementSibling ?? divider;
		const dividerTop = Math.ceil(question.getBoundingClientRect().bottom - el.getBoundingClientRect().top + el.scrollTop) + 5;
		// The divider must be able to reach the top so the headword is fully scrolled out, never cut mid-glyph:
		// when the answer is short, add the missing room below it (blank space under the answer).
		extraBottom = Math.max(0, Math.ceil(dividerTop - overflow));
		await tick();
		const top = Math.max(0, dividerTop);
		if (top <= el.scrollTop) return;
		const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
		el.scrollTo({ top, behavior: reduce ? 'auto' : 'smooth' });
	}

	// Set when the revealed card does not fit: the question then steps back (smaller headword, less air).
	// Only then, so on a card that fits the front stays exactly where it was.
	let compact = $state(false);
	/** Extra padding under the answer so the divider can scroll to the top edge (see bringAnswerIntoView). */
	let extraBottom = $state(0);

	$effect(() => {
		const flipped = s.flipped;
		void s.current?.card.id;
		void s.rendered;
		compact = false;
		extraBottom = 0;
		const el = body;
		if (!el) return;
		void tick().then(async () => {
			if (flipped && el.scrollHeight > el.clientHeight) {
				compact = true;
				await tick();
				await bringAnswerIntoView(el);
			}
			measure();
		});
		if (typeof ResizeObserver === 'undefined') return;
		const ro = new ResizeObserver(measure);
		ro.observe(el);
		for (const child of el.children) ro.observe(child);
		return () => ro.disconnect();
	});

	/* ---------- header figures ---------- */

	// ADR-037: the scheduled session's figures come from `loop` (ready now = `ready.total`, C3); a drill counts its
	// own list. The bar is this visit's answers against those plus what is ready now.
	const remaining = $derived(s.loop ? s.loop.ready.total : s.items.length);
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
		{ label: rv('rv.blacklist'), icon: 'close', key: 'X', disabled: !s.current, onselect: () => void s.blacklist() },
		{ label: rv('rv.shortcuts'), icon: 'info', key: '?', onselect: () => (helpOpen = true) }
	]);

	/* ---------- keyboard ---------- */

	function onKeydown(e: KeyboardEvent): void {
		if (e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented) return;
		if (e.repeat) return; // held keys never act twice (grade, reveal, undo, bury ...)
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
			s.key(k, e.repeat);
			return;
		}
		if (/^[1-5]$/.test(k)) {
			if (s.key(k, e.repeat)) e.preventDefault();
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
			case 'x':
				void s.blacklist();
				break;
			case 'f':
				s.toggleFurigana();
				break;
			case 'p':
				frame?.playFirst();
				break;
		}
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
		['X', rv('rv.key.blacklist')],
		['?', rv('rv.key.help')],
		['Esc', rv('rv.key.leave')]
	]);
</script>

<svelte:window onkeydown={onKeydown} />
<svelte:document onvisibilitychange={() => document.visibilityState === 'visible' && s.onVisible()} />
<svelte:head><title>{rv('rv.title')} · Kotobaya</title></svelte:head>

<div class="review">
	<header class="bar">
		<IconButton icon="close" href={leaveHref} label={leaveLabel} />
		<div class="status">
			<div class="line">
				<h1 class="mode">{modeLabel}</h1>
				{#if s.loaded && s.loop && !s.view?.legacy}
					<p class="counts">
						<span class="count learning"><i aria-hidden="true"></i>{rv('rv.count.learning', { n: s.loop.ready.learning })}</span>
						<span class="count due"><i aria-hidden="true"></i>{rv('rv.count.review', { n: s.loop.ready.review })}</span>
						<span class="count new"><i aria-hidden="true"></i>{rv('rv.count.newOf', { today: s.loop._new.today, goal: s.loop._new.goal })}</span>
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

	{#if s.notice}
		<div class="notice">
			{#if s.notice === 'clockSkew'}
				<Banner tone="error" title={rv('rv.clockSkew.title')}>
					{rv('rv.clockSkew.body')}
					{#snippet action()}<Button size="sm" variant="outline" icon="sync" onclick={() => void s.retryAnswer()}>{rv('rv.slow.retry')}</Button>{/snippet}
				</Banner>
			{:else if s.notice === 'answerFailed'}
				<Banner tone="error" title={rv('rv.answerFailed.title')}>
					{rv('rv.answerFailed.body')}
					{#snippet action()}<Button size="sm" variant="outline" icon="sync" onclick={() => void s.retryAnswer()}>{rv('rv.slow.retry')}</Button>{/snippet}
				</Banner>
			{:else}
				<Banner tone="error" title={rv('rv.undoFailed.title')}>
					{rv('rv.undoFailed.body')}
					{#snippet action()}<Button size="sm" variant="outline" icon="undo" onclick={() => void s.undo()}>{rv('rv.slow.retry')}</Button>{/snippet}
				</Banner>
			{/if}
		</div>
	{/if}

	{#if bigDay && s.current}
		<div class="notice">
			<Banner title={rv('rv.big.title', { n: remaining })}>{rv('rv.big.body')}</Banner>
		</div>
	{/if}

	<section class="stage" aria-label={rv('rv.title')}>
		{#if s.phase === 'loading' || (s.phase === 'settling' && !s.display)}
			<div class="card" aria-busy="true">
				<div class="card-body skeleton">
					<span class="kb-sr">{rv('rv.loading')}</span>
					<Skeleton shape="block" height="4.5rem" />
					<Skeleton shape="text" count={2} />
					{#if slow}
						<div class="slow" role="status">
							<p>{rv('rv.slow.loading')}</p>
							<Button variant="outline" icon="sync" onclick={() => s.retry()}>{rv('rv.slow.retry')}</Button>
						</div>
					{/if}
				</div>
			</div>
		{:else if s.phase === 'error'}
			<div class="finished">
				<LoadError size="page" title={rv('rv.loadFailed')} onretry={() => (s.loadFailed ? s.retry() : void s.reload())} />
			</div>
		{:else if s.phase === 'drillUnavailable'}
			<div class="finished">
				<EmptyState size="page" art="info" title={rv('rv.drillUnavailable.title')} body={rv('rv.drillUnavailable.body')}>
					{#snippet action()}<Button variant="primary" href="/struggle">{rv('rv.leaveDrill')}</Button>{/snippet}
				</EmptyState>
			</div>
		{:else if s.phase === 'drillDone'}
			<div class="finished" aria-live="polite">
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
			</div>
		{:else if s.phase === 'pending'}
			<!-- Not the end: the loop says cards are ready but none was served yet; fetched again by itself. -->
			<div class="finished" aria-live="polite">
				<div class="wait">
					<Icon name="clock" size={40} />
					<h2>{rv('rv.pending.title')}</h2>
					{#if s.refreshFailed}
						<!-- One failed check does not replace this screen: the next check is already scheduled. -->
						<p class="inline-error" role="status">{rv('rv.checkFailed')}</p>
					{/if}
					<p class="muted">{s.pendingStopped ? rv('rv.pending.stopped') : rv('rv.pending.body')}</p>
					<div class="done-actions">
						<Button
							variant={s.pendingStopped ? 'primary' : 'outline'}
							icon="sync"
							loading={s.busy}
							onclick={() => s.reload()}>{s.pendingStopped ? rv('rv.pending.refresh') : rv('rv.done.check')}</Button
						>
						{#if s.history.length > 0}
							<Button variant="quiet" icon="undo" onclick={() => s.undo()}>{rv('rv.undo')}</Button>
						{/if}
						<Button variant="quiet" href="/">{rv('rv.done.today')}</Button>
					</div>
				</div>
			</div>
		{:else if s.display}
			{@const d = s.display}
			<!-- The card on screen; while its answer is saved and nothing else is left it stays (inert, dimmed)
			     instead of a skeleton flash. Keyed on the card, so a refetch that keeps it never remounts it. -->
			{#key d.card.id}
				<article
					class="card enter"
					class:flipped={s.flipped || s.phase === 'settling'}
					class:held={s.phase === 'settling'}
					class:compact
					class:more-below={moreBelow}
					aria-live="polite"
					aria-busy={s.phase === 'settling'}
				>
					{#if s.reviewTypeKey || d.card.note?.type || d.isNew}
						<div class="chip-row">
							<p class="tag">{s.reviewTypeKey ? tr(s.reviewTypeKey) : ''}</p>
							<span class="kind-badge">{d.card.note?.type ? rv(`rv.kind.${d.card.note.type}`) : ''}</span>
							<span class="new-badge">{d.isNew ? rv('rv.newBadge') : ''}</span>
						</div>
					{/if}
					<div class="card-body" bind:this={body} onscroll={measure} style:padding-bottom={extraBottom > 0 ? `calc(var(--kb-space-10) + ${extraBottom}px)` : undefined}>
						{#if s.rendered === null}
							<!-- unreachable: display is set -->
						{:else if s.rendered.ok}
							<CardFrame bind:this={frame} rendered={s.rendered.card} furigana={s.furigana} reveal={s.flipped || s.phase === 'settling'} />
							{#if s.noExample && s.flipped}
								<p class="note">{tr('review.noExampleSentence')}</p>
							{/if}
							{#if s.flipped && s.current}
								<BackExtras item={s.current} />
								<!-- New tab: leaving would drop the session; a link never takes Space/1-5 (see onKeydown). -->
								<p class="dict-link">
									<DictionaryLink
										look="text"
										newTab
										seq={s.current.entry?.seq ?? s.current.card.note?.entrySeq}
										kanji={s.current.card.note?.kanjiLiteral}
									/>
								</p>
							{/if}
						{:else}
							<EmptyState tone="error" art="alert" title={rv('rv.renderFailed')} body={s.rendered.detail} />
						{/if}
					</div>
				</article>
			{/key}
		{:else if s.phase === 'goalReached' || s.phase === 'doneForToday' || s.phase === 'waiting' || s.phase === 'done'}
			<!-- ADR-037 C4: no card, so the screen is `loop.phase` only. -->
			<LoopEnd
				phase={s.phase}
				view={s.view}
				busy={s.busy}
				canUndo={s.history.length > 0}
				checkFailed={s.refreshFailed}
				onkeepgoing={() => void s.keepGoingOn()}
				onundo={() => void s.undo()}
				onreload={() => void s.reload()}
			/>
		{/if}
	</section>

	<footer class="actions" bind:this={actionsEl}>
		{#if !s.loaded || s.display}
			<div class="slot">
				{#if s.gradeLocked}
					<!-- An unconfirmed answer: a regrade would reuse its id (the server keeps the first), so only Retry. -->
					<div class="swap">
						<Button variant="primary" size="lg" full icon="sync" onclick={() => void s.retryAnswer()}>{rv('rv.answerFailed.retry')}</Button>
					</div>
				{:else if s.phase === 'settling'}
					<div class="swap">
						<Button variant="primary" size="lg" full disabled loading>{slow ? rv('rv.slow.saving') : rv('rv.saving')}</Button>
					</div>
				{:else if !s.flipped || !s.current}
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
		--body-pt: clamp(24px, 12dvh, 112px);
		--body-px: clamp(var(--kb-space-4), 5vw, var(--kb-space-12));
		padding: var(--body-pt) var(--body-px) var(--kb-space-10);
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
	/* Normal flow above the card content (never an overlay), so text can not sit under a chip. */
	.chip-row {
		display: grid;
		grid-template-columns: 1fr auto 1fr;
		align-items: center;
		gap: var(--kb-space-2);
		flex: none;
		padding: var(--kb-space-3) var(--kb-space-4) 0;
	}
	.chip-row > :empty {
		visibility: hidden;
	}
	.tag {
		margin: 0;
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.new-badge {
		justify-self: end;
		font: var(--kb-type-caption);
		color: var(--kb-color-state-new);
		border: 1px solid currentColor;
		border-radius: var(--kb-radius-sm, 4px);
		padding: 0 var(--kb-space-2);
	}
	.kind-badge {
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
		border: 1px solid currentColor;
		border-radius: var(--kb-radius-sm, 4px);
		padding: 0 var(--kb-space-2);
	}
	.dict-link {
		display: flex;
		justify-content: center;
		margin-top: var(--kb-space-4);
	}
	.dict-link:empty {
		display: none;
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
	/* Revealed and too tall (large text, short window): the question steps back so the answer fits. The
	   top padding and the headword are capped by the card's own height. */
	.compact .card-body {
		--body-pt: min(clamp(24px, 12dvh, 112px), 8cqh);
		padding-top: var(--body-pt);
	}
	.compact .card-body :global(.kb-expression) {
		font-size: min(clamp(3rem, 2.25rem + 3.2vw, 5rem), max(2rem, 13cqh));
	}
	.compact .card-body :global(.kb-literal) {
		font-size: min(clamp(4.5rem, 3.4rem + 4.5vw, 7rem), max(2.5rem, 18cqh));
	}
	.compact .card-body :global(.kb-divider) {
		margin: min(1rem, 3cqh) 0;
	}

	/* ---------- held card (answer saving), inline notices ---------- */
	.card.held .card-body {
		opacity: 0.6;
		transition: opacity var(--kb-duration-fast) var(--kb-ease-standard);
	}
	.inline-error {
		margin: 0;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-danger, var(--kb-color-text-muted));
	}

	/* ---------- slow load, waiting ---------- */
	.slow {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--kb-space-3);
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
		text-align: center;
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
	.muted {
		margin: 0;
		max-width: 28rem;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
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
