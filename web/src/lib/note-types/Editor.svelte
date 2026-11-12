<script lang="ts">
	import { renderCard, type RenderedCard } from '../core/index';
	import { CardView } from '../review/index';
	import {
		BASE_TYPES,
		KEY_PATTERN,
		previewOptions,
		sampleFields,
		toCoreNoteType,
		validate,
		type FieldKind,
		type NoteTypeDraft
	} from './draft';
	import { tn, type NoteTypeMessageKey } from './messages';
	import { pushToast } from '../toasts.svelte';

	let {
		draft = $bindable(),
		isNew,
		busy,
		submitLabel,
		onsubmit,
		ondelete
	}: {
		draft: NoteTypeDraft;
		isNew: boolean;
		busy: boolean;
		submitLabel: string;
		onsubmit: () => void;
		ondelete?: () => void;
	} = $props();

	const FIELD_KINDS: FieldKind[] = ['text', 'audio', 'image'];

	let newKind = $state('');
	let previewKind = $state('');
	let previewSide = $state<'front' | 'back'>('front');
	let furigana = $state(true);
	let pitch = $state(false);
	let mnemonic = $state(tn('nt.preview.sampleMnemonic'));
	let samples = $state<Record<string, string>>({});

	// Keep sample values in step with the field list: keep what the user typed, seed new keys.
	$effect(() => {
		const seeded = sampleFields(draft);
		const next: Record<string, string> = {};
		let changed = false;
		for (const f of draft.fields) {
			const cur = samples[f.key];
			next[f.key] = cur ?? seeded[f.key] ?? '';
			if (cur === undefined) changed = true;
		}
		if (changed || Object.keys(samples).length !== draft.fields.length) samples = next;
	});

	// The previewed kind must exist.
	$effect(() => {
		if (!draft.kinds.some((k) => k.kind === previewKind)) previewKind = draft.kinds[0]?.kind ?? '';
	});

	type Preview = { ok: true; card: RenderedCard } | { ok: false; detail: string };

	// Re-rendered on every edit (draft, samples, toggles): the live preview.
	const preview = $derived.by((): Preview | null => {
		if (previewKind === '') return null;
		try {
			const card = renderCard({
				noteType: toCoreNoteType(draft),
				kind: previewKind,
				fields: Object.fromEntries(draft.fields.map((f) => [f.key, samples[f.key] ?? ''])),
				mnemonic,
				side: previewSide,
				options: previewOptions(furigana, pitch)
			});
			return { ok: true, card };
		} catch (e) {
			// The renderer reports template problems as warnings; a throw here is a host-side bug.
			return { ok: false, detail: e instanceof Error ? e.message : String(e) };
		}
	});

	function warningText(key: string): string {
		const code = key.replace(/^render\.warning\./, '');
		const k = `nt.warning.${code}`;
		return k in WARNING_KEYS ? tn(k as NoteTypeMessageKey) : tn('nt.warning.other', { code });
	}
	const WARNING_KEYS: Record<string, true> = {
		'nt.warning.unknown_placeholder': true,
		'nt.warning.dropped_tag': true
	};

	function addField(): void {
		let n = draft.fields.length + 1;
		while (draft.fields.some((f) => f.key === `field_${n}`)) n++;
		draft.fields.push({ key: `field_${n}`, label: '', kind: 'text', saved: false });
	}

	function addKind(): void {
		const k = newKind.trim();
		if (!KEY_PATTERN.test(k)) {
			pushToast(tn('nt.kind.invalid'), 'error');
			return;
		}
		if (draft.kinds.some((x) => x.kind === k)) {
			pushToast(tn('nt.kind.exists', { kind: k }), 'error');
			return;
		}
		draft.kinds.push({ kind: k, enabled: false, front: '', back: '' });
		newKind = '';
		previewKind = k;
	}

	function problemText(): string | null {
		const p = validate(draft);
		if (!p) return null;
		switch (p.code) {
			case 'name':
				return tn('nt.nameRequired');
			case 'fields':
				return tn('nt.fieldsRequired');
			case 'fieldKey':
				return tn('nt.field.invalidKey');
			case 'fieldDup':
				return tn('nt.field.dupKey', { key: p.key });
			case 'kindKey':
				return tn('nt.kind.invalid');
			case 'noDefault':
				return tn('nt.kind.noDefault');
		}
	}

	function submit(e: SubmitEvent): void {
		e.preventDefault();
		const msg = problemText();
		if (msg) {
			pushToast(msg, 'error');
			return;
		}
		onsubmit();
	}
</script>

<div class="layout">
	<form onsubmit={submit}>
		<label>{tn('nt.name')} <input type="text" bind:value={draft.name} maxlength="100" /></label>
		<label>
			{tn('nt.baseType')}
			<select bind:value={draft.baseType} disabled={!isNew}>
				{#each BASE_TYPES as b (b)}
					<option value={b}>{tn(`nt.base.${b}`)}</option>
				{/each}
			</select>
		</label>

		<fieldset>
			<legend>{tn('nt.fields')}</legend>
			<p class="help">{tn('nt.field.keyHelp')}</p>
			{#each draft.fields as f, i (i)}
				<div class="row">
					<input
						type="text"
						aria-label={tn('nt.field.key')}
						placeholder={tn('nt.field.key')}
						bind:value={f.key}
						readonly={f.saved}
					/>
					<input
						type="text"
						aria-label={tn('nt.field.label')}
						placeholder={tn('nt.field.label')}
						bind:value={f.label}
					/>
					<select aria-label={tn('nt.field.kind')} bind:value={f.kind}>
						{#each FIELD_KINDS as k (k)}
							<option value={k}>{tn(`nt.field.kind.${k}`)}</option>
						{/each}
					</select>
					{#if !f.saved}
						<button
							class="kb-btn kb-btn-quiet"
							type="button"
							onclick={() => draft.fields.splice(i, 1)}>{tn('nt.field.remove')}</button
						>
					{/if}
				</div>
			{/each}
			<button class="kb-btn kb-btn-quiet" type="button" onclick={addField}>{tn('nt.field.add')}</button>
		</fieldset>

		<fieldset>
			<legend>{tn('nt.kinds')}</legend>
			<p class="help">{tn('nt.kind.builtinHelp')}</p>
			{#each draft.kinds as k, i (k.kind)}
				<div class="kind">
					<h3>{k.kind}</h3>
					<label class="inline">
						<input type="checkbox" bind:checked={k.enabled} />
						{tn('nt.kind.enabled')}
					</label>
					<label>{tn('nt.kind.front')} <textarea rows="4" spellcheck="false" bind:value={k.front}></textarea></label>
					<label>{tn('nt.kind.back')} <textarea rows="4" spellcheck="false" bind:value={k.back}></textarea></label>
					<button class="kb-btn kb-btn-quiet" type="button" onclick={() => draft.kinds.splice(i, 1)}>
						{tn('nt.kind.remove')}
					</button>
				</div>
			{/each}
			<div class="row">
				<input type="text" aria-label={tn('nt.kind.newName')} placeholder={tn('nt.kind.newName')} bind:value={newKind} />
				<button class="kb-btn kb-btn-quiet" type="button" onclick={addKind}>{tn('nt.kind.add')}</button>
			</div>
		</fieldset>

		<label>{tn('nt.css')} <textarea rows="6" spellcheck="false" bind:value={draft.css}></textarea></label>

		<div class="actions">
			<button class="kb-btn" type="submit" disabled={busy}>{submitLabel}</button>
			{#if ondelete}
				<button class="kb-btn kb-btn-quiet" type="button" onclick={ondelete}>{tn('nt.delete')}</button>
			{/if}
		</div>
	</form>

	<section class="preview" aria-label={tn('nt.preview')}>
		<h2>{tn('nt.preview')}</h2>
		<div class="row">
			<label class="inline">
				{tn('nt.preview.kind')}
				<select bind:value={previewKind}>
					{#each draft.kinds as k (k.kind)}
						<option value={k.kind}>{k.kind}</option>
					{/each}
				</select>
			</label>
			<label class="inline">
				{tn('nt.preview.side')}
				<select bind:value={previewSide}>
					<option value="front">{tn('nt.preview.front')}</option>
					<option value="back">{tn('nt.preview.back')}</option>
				</select>
			</label>
			<label class="inline"><input type="checkbox" bind:checked={furigana} /> {tn('nt.preview.furigana')}</label>
			<label class="inline"><input type="checkbox" bind:checked={pitch} /> {tn('nt.preview.pitch')}</label>
		</div>

		{#if preview === null}
			<p class="help">{tn('nt.kind.noDefault')}</p>
		{:else if preview.ok}
			<CardView rendered={preview.card} />
			{#if preview.card.warnings.length > 0}
				<div class="warnings" role="status">
					<strong>{tn('nt.preview.warnings')}</strong>
					<ul>
						{#each [...new Set(preview.card.warnings)] as w (w)}
							<li>{warningText(w)}</li>
						{/each}
					</ul>
				</div>
			{:else}
				<p class="help">{tn('nt.preview.noWarnings')}</p>
			{/if}
		{:else}
			<p class="error" role="alert">{tn('nt.preview.failed', { detail: preview.detail })}</p>
		{/if}

		<fieldset>
			<legend>{tn('nt.preview.samples')}</legend>
			{#each draft.fields as f (f.key)}
				<label>{f.key} <input type="text" bind:value={samples[f.key]} /></label>
			{/each}
			<label>{tn('nt.preview.mnemonic')} <input type="text" bind:value={mnemonic} /></label>
		</fieldset>
	</section>
</div>

<style>
	.layout {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(22rem, 1fr));
		gap: var(--kb-space-4);
		align-items: start;
	}
	form,
	fieldset {
		display: grid;
		gap: var(--kb-space-2);
	}
	label {
		display: grid;
		gap: var(--kb-space-1);
	}
	label.inline {
		display: inline-flex;
		gap: var(--kb-space-2);
		align-items: center;
	}
	textarea {
		font-family: var(--kb-font-mono);
		width: 100%;
	}
	.row {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		align-items: center;
	}
	.kind {
		display: grid;
		gap: var(--kb-space-2);
		padding: var(--kb-space-3);
		background: var(--kb-surface);
		border: 1px solid var(--kb-border);
		border-radius: var(--kb-radius);
	}
	.actions {
		display: flex;
		gap: var(--kb-space-2);
	}
	.preview {
		position: sticky;
		top: 4rem;
		display: grid;
		gap: var(--kb-space-2);
	}
	.help {
		color: var(--kb-text-muted);
		margin: 0;
	}
	.warnings,
	.error {
		background: var(--kb-error-bg);
		color: var(--kb-error);
		padding: var(--kb-space-2) var(--kb-space-3);
		border-radius: var(--kb-radius);
		margin: 0;
	}
</style>
