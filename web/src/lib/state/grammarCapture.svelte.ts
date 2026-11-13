// Grammar capture panel (used by the dictionary examples and the reader): mark a span in a sentence,
// attach it to a new or existing grammar point, and test a draft detection rule on the server.
import type { GrammarPoint, GrammarRuleTestResult, Sentence } from '../api/generated';
import { apiConfig, readProblem, ResponseError } from '../api/client';
import { GrammarApi } from '../api/generated';
import { tg } from '../grammar-capture/messages';
import { draftRule } from '../grammar-capture/rule';
import { uuidv7 } from '../review/model';
import { pushToast } from '../toasts.svelte';

export type { GrammarPoint, Sentence } from '../api/generated';
export { selectionOffsets } from '../grammar-capture/rule';

const api = new GrammarApi(apiConfig);

/** Construct during component init (it registers the debounced point search). */
export class GrammarCaptureState {
	span = $state<{ start: number; end: number } | null>(null);
	mode = $state<'new' | 'existing'>('new');
	title = $state('');
	pattern = $state('');
	rule = $state('');
	q = $state('');
	results = $state<GrammarPoint[]>([]);
	chosen = $state<GrammarPoint | null>(null);
	testResult = $state<GrammarRuleTestResult | null>(null);
	ruleError = $state('');
	busy = $state(false);
	error = $state('');
	// Id of the new point once created; retries reuse it (idempotent create).
	pendingId = $state('');
	private created = false;

	readonly #sentence: () => Sentence;
	readonly #onclose: () => void;

	readonly spanText = $derived.by(() => (this.span ? this.#sentence().text.slice(this.span.start, this.span.end) : ''));

	constructor(sentence: () => Sentence, onclose: () => void) {
		this.#sentence = sentence;
		this.#onclose = onclose;

		$effect(() => {
			const query = this.q.trim();
			if (this.mode !== 'existing' || !query) {
				this.results = [];
				return;
			}
			const h = setTimeout(() => {
				api
					.listGrammarPoints({ q: query, limit: 10 })
					.then((p) => (this.results = p.items))
					.catch(() => {});
			}, 250);
			return () => clearTimeout(h);
		});
	}

	/** The user selected `o` (UTF-16 offsets into the sentence text). */
	capture(o: { start: number; end: number } | null): void {
		if (!o) return;
		const sentence = this.#sentence();
		this.span = o;
		const surface = sentence.text.slice(o.start, o.end);
		this.rule = draftRule(sentence, o.start, o.end);
		if (!this.pendingId) {
			this.title = surface;
			this.pattern = surface;
		}
		this.testResult = null;
		this.ruleError = '';
	}

	choose(p: GrammarPoint): void {
		this.chosen = p;
	}

	/** Shows a 422 inline as a rule error; other errors are toasted by the client. */
	private async ruleProblem(e: unknown): Promise<void> {
		if (e instanceof ResponseError && e.response.status === 422) {
			const p = await readProblem(e.response);
			this.ruleError = tg('gc.ruleError', { detail: p?.errors?.[0]?.detail ?? p?.detail ?? '' });
		}
	}

	// A new point (with its draft rule) is created on first test or save so the server
	// tester has an id to work against.
	private async ensureNewPoint(): Promise<string | null> {
		if (!this.pendingId) this.pendingId = uuidv7();
		if (this.created) return this.pendingId;
		try {
			await api.createGrammarPoint({
				grammarPointCreate: {
					id: this.pendingId,
					title: this.title.trim(),
					pattern: this.pattern.trim(),
					detectionRules: this.rule.trim() ? [{ rule: this.rule.trim(), draft: true }] : []
				}
			});
			this.created = true;
			return this.pendingId;
		} catch (e) {
			await this.ruleProblem(e);
			return null;
		}
	}

	async test(): Promise<void> {
		this.error = '';
		this.ruleError = '';
		this.busy = true;
		try {
			let pointId: string | null;
			if (this.mode === 'existing') {
				pointId = this.chosen?.id ?? null;
			} else {
				if (!this.title.trim()) {
					this.error = tg('gc.needTitle');
					return;
				}
				pointId = await this.ensureNewPoint();
			}
			if (!pointId) return;
			this.testResult = await api.testGrammarRules({
				grammarPointId: pointId,
				grammarRuleTestRequest: { rules: [{ rule: this.rule.trim(), draft: true }], limit: 20 }
			});
		} catch (e) {
			await this.ruleProblem(e);
		} finally {
			this.busy = false;
		}
	}

	async save(): Promise<void> {
		this.error = '';
		this.ruleError = '';
		const span = this.span;
		if (!span) {
			this.error = tg('gc.needSpan');
			return;
		}
		const sentence = this.#sentence();
		this.busy = true;
		try {
			let pointId: string | null;
			if (this.mode === 'existing') {
				if (!this.chosen) {
					this.error = tg('gc.needPoint');
					return;
				}
				pointId = this.chosen.id;
			} else {
				if (!this.title.trim()) {
					this.error = tg('gc.needTitle');
					return;
				}
				pointId = await this.ensureNewPoint();
				if (!pointId) return;
			}
			await api.putGrammarSpan({
				grammarPointId: pointId,
				sentenceId: sentence.id,
				startOffset: span.start,
				endOffset: span.end,
				grammarSpanPut: { mediaId: sentence.mediaId }
			});
			if (this.mode === 'existing' && this.rule.trim()) {
				try {
					await api.updateGrammarPoint({
						grammarPointId: pointId,
						grammarPointPatch: {
							detectionRules: [...(this.chosen?.detectionRules ?? []), { rule: this.rule.trim(), draft: true }]
						}
					});
				} catch (e) {
					await this.ruleProblem(e);
					return;
				}
			}
			pushToast(tg('gc.saved'), 'info');
			this.#onclose();
		} catch {
			/* toast shown */
		} finally {
			this.busy = false;
		}
	}
}
