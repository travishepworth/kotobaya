<script lang="ts">
	import { apiConfig, readProblem, ResponseError } from '../api/client';
	import { GrammarApi } from '../api/generated';
	import type { GrammarPoint, GrammarRuleTestResult, Sentence } from '../api/generated';
	import { uuidv7 } from '../review/model';
	import { pushToast } from '../toasts.svelte';
	import { tg } from './messages';
	import { draftRule, selectionOffsets } from './rule';

	let { sentence, onclose }: { sentence: Sentence; onclose: () => void } = $props();

	const api = new GrammarApi(apiConfig);
	let textEl = $state<HTMLElement | null>(null);
	let span = $state<{ start: number; end: number } | null>(null);
	let mode = $state<'new' | 'existing'>('new');
	let title = $state('');
	let pattern = $state('');
	let rule = $state('');
	let q = $state('');
	let results = $state<GrammarPoint[]>([]);
	let chosen = $state<GrammarPoint | null>(null);
	let testResult = $state<GrammarRuleTestResult | null>(null);
	let ruleError = $state('');
	let busy = $state(false);
	let error = $state('');
	// Id of the new point once created; retries reuse it (idempotent create).
	let pendingId = $state('');
	let created = false;

	const spanText = $derived(span ? sentence.text.slice(span.start, span.end) : '');

	function capture(): void {
		if (!textEl) return;
		const o = selectionOffsets(textEl);
		if (!o) return;
		span = o;
		const surface = sentence.text.slice(o.start, o.end);
		rule = draftRule(sentence, o.start, o.end);
		if (!pendingId) {
			title = surface;
			pattern = surface;
		}
		testResult = null;
		ruleError = '';
	}

	$effect(() => {
		const query = q.trim();
		if (mode !== 'existing' || !query) {
			results = [];
			return;
		}
		const h = setTimeout(() => {
			api
				.listGrammarPoints({ q: query, limit: 10 })
				.then((p) => (results = p.items))
				.catch(() => {});
		}, 250);
		return () => clearTimeout(h);
	});

	/** Shows a 422 inline as a rule error; other errors are toasted by the client. */
	async function ruleProblem(e: unknown): Promise<void> {
		if (e instanceof ResponseError && e.response.status === 422) {
			const p = await readProblem(e.response);
			ruleError = tg('gc.ruleError', { detail: p?.errors?.[0]?.detail ?? p?.detail ?? '' });
		}
	}

	// A new point (with its draft rule) is created on first test or save so the server
	// tester has an id to work against.
	async function ensureNewPoint(): Promise<string | null> {
		if (!pendingId) pendingId = uuidv7();
		if (created) return pendingId;
		try {
			await api.createGrammarPoint({
				grammarPointCreate: {
					id: pendingId,
					title: title.trim(),
					pattern: pattern.trim(),
					detectionRules: rule.trim() ? [{ rule: rule.trim(), draft: true }] : []
				}
			});
			created = true;
			return pendingId;
		} catch (e) {
			await ruleProblem(e);
			return null;
		}
	}

	async function test(): Promise<void> {
		error = '';
		ruleError = '';
		busy = true;
		try {
			let pointId: string | null;
			if (mode === 'existing') {
				pointId = chosen?.id ?? null;
			} else {
				if (!title.trim()) {
					error = tg('gc.needTitle');
					return;
				}
				pointId = await ensureNewPoint();
			}
			if (!pointId) return;
			testResult = await api.testGrammarRules({
				grammarPointId: pointId,
				grammarRuleTestRequest: { rules: [{ rule: rule.trim(), draft: true }], limit: 20 }
			});
		} catch (e) {
			await ruleProblem(e);
		} finally {
			busy = false;
		}
	}

	async function save(): Promise<void> {
		error = '';
		ruleError = '';
		if (!span) {
			error = tg('gc.needSpan');
			return;
		}
		busy = true;
		try {
			let pointId: string | null;
			if (mode === 'existing') {
				if (!chosen) {
					error = tg('gc.needPoint');
					return;
				}
				pointId = chosen.id;
			} else {
				if (!title.trim()) {
					error = tg('gc.needTitle');
					return;
				}
				pointId = await ensureNewPoint();
				if (!pointId) return;
			}
			await api.putGrammarSpan({
				grammarPointId: pointId,
				sentenceId: sentence.id,
				startOffset: span.start,
				endOffset: span.end,
				grammarSpanPut: { mediaId: sentence.mediaId }
			});
			if (mode === 'existing' && rule.trim()) {
				try {
					await api.updateGrammarPoint({
						grammarPointId: pointId,
						grammarPointPatch: {
							detectionRules: [...(chosen?.detectionRules ?? []), { rule: rule.trim(), draft: true }]
						}
					});
				} catch (e) {
					await ruleProblem(e);
					return;
				}
			}
			pushToast(tg('gc.saved'), 'info');
			onclose();
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}
</script>

<section class="capture" aria-label={tg('gc.title')}>
	<h3>{tg('gc.title')}</h3>
	<p class="muted">{tg('gc.select')}</p>
	<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
	<p class="text" lang="ja" bind:this={textEl} onmouseup={capture} onkeyup={capture}>{sentence.text}</p>
	<p>{span ? tg('gc.span', { span: spanText }) : tg('gc.noSpan')}</p>

	<label><input type="radio" bind:group={mode} value="new" /> {tg('gc.mode.new')}</label>
	<label><input type="radio" bind:group={mode} value="existing" /> {tg('gc.mode.existing')}</label>

	{#if mode === 'new'}
		<label>{tg('gc.field.title')} <input bind:value={title} disabled={!!pendingId} /></label>
		<label>{tg('gc.field.pattern')} <input bind:value={pattern} disabled={!!pendingId} /></label>
	{:else}
		<input type="search" placeholder={tg('gc.search')} aria-label={tg('gc.search')} bind:value={q} />
		{#if q.trim() && results.length === 0}<p class="muted">{tg('gc.noResults')}</p>{/if}
		<ul>
			{#each results as p (p.id)}
				<li>
					<label>
						<input
							type="radio"
							name="gc-point"
							checked={chosen?.id === p.id}
							onchange={() => (chosen = p)}
						/>
						<span lang="ja">{p.title}</span>
						{#if p.pattern}<small lang="ja">{p.pattern}</small>{/if}
					</label>
				</li>
			{/each}
		</ul>
	{/if}

	<label>{tg('gc.field.rule')} <input lang="ja" bind:value={rule} /></label>
	<button
		type="button"
		onclick={() => void test()}
		disabled={busy || !rule.trim() || (mode === 'existing' && !chosen)}
	>
		{busy ? tg('gc.testing') : tg('gc.test')}
	</button>
	{#if ruleError}<p class="err" role="alert">{ruleError}</p>{/if}
	{#if testResult}
		<p>{tg('gc.testResult', { count: testResult.matches.length, scanned: testResult.scanned })}</p>
		{#if testResult.truncated}<p class="muted">{tg('gc.testTruncated')}</p>{/if}
		<ul lang="ja">
			{#each testResult.matches as m (m.sentence.id)}
				{@const sp = m.spans[0]}
				<li>
					{#if sp}{m.sentence.text.slice(0, sp.startOffset)}<mark
							>{m.sentence.text.slice(sp.startOffset, sp.endOffset)}</mark
						>{m.sentence.text.slice(sp.endOffset)}{:else}{m.sentence.text}{/if}
				</li>
			{/each}
		</ul>
	{/if}

	{#if error}<p class="err" role="alert">{error}</p>{/if}
	<div>
		<button type="button" onclick={() => void save()} disabled={busy}>
			{busy ? tg('gc.saving') : tg('gc.save')}
		</button>
		<button type="button" onclick={onclose}>{tg('gc.cancel')}</button>
	</div>
</section>

<style>
	.capture {
		display: grid;
		gap: var(--kb-space-2);
		margin: var(--kb-space-2) 0;
		padding: var(--kb-space-3);
		border: 1px solid currentColor;
	}
	.text {
		font-size: 1.2rem;
		user-select: text;
	}
	.err {
		color: crimson;
	}
	ul {
		list-style: none;
		padding: 0;
	}
</style>
