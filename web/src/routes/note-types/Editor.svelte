<script lang="ts">
	// Note-type editor: Fields · Templates · Style on the left, the live card (real card.css, both themes) on the right.
	// Below 1024 px the preview becomes its own tab.
	import Banner from '../../lib/ui/Banner.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import CardFrame from '../../lib/ui/CardFrame.svelte';
	import EmptyState from '../../lib/ui/EmptyState.svelte';
	import IconButton from '../../lib/ui/IconButton.svelte';
	import SegmentedControl from '../../lib/ui/SegmentedControl.svelte';
	import Select from '../../lib/ui/Select.svelte';
	import Switch from '../../lib/ui/Switch.svelte';
	import Tabs from '../../lib/ui/Tabs.svelte';
	import TextArea from '../../lib/ui/TextArea.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { ut } from '../../lib/ui/messages';
	import { tn } from '../../lib/note-types/messages';
	import { BASE_TYPES, NoteTypeEditorState, warningText, type FieldKind, type NoteTypeDraft } from '../../lib/state/noteTypes.svelte';
	import { nt } from './messages';

	interface Props {
		draft: NoteTypeDraft;
		isNew: boolean;
		busy: boolean;
		readonlyBase?: boolean;
		submitLabel: string;
		onsubmit: () => void;
	}
	let { draft = $bindable(), isNew, busy, submitLabel, onsubmit }: Props = $props();

	const FIELD_KINDS: FieldKind[] = ['text', 'audio', 'image'];
	const s = new NoteTypeEditorState(() => draft);

	type Tab = 'fields' | 'templates' | 'style' | 'preview';
	let tab = $state<Tab>('fields');
	let wide = $state(true);
	// The preview starts in the app's current theme.
	const root = document.documentElement;
	const appDark =
		root.dataset.theme === 'dark' || (root.dataset.theme !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches);
	let previewTheme = $state<'light' | 'dark'>(appDark ? 'dark' : 'light');
	let form: HTMLFormElement | undefined = $state();
	// Preview beside the editor once there are 56 rem of room (follows the text size).
	$effect(() => {
		if (!form) return;
		const el = form;
		const ro = new ResizeObserver(() => {
			const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
			wide = el.clientWidth >= 56 * rem;
			if (wide && tab === 'preview') tab = 'fields';
		});
		ro.observe(el);
		return () => ro.disconnect();
	});

	function submit(e: SubmitEvent): void {
		e.preventDefault();
		if (s.canSubmit()) onsubmit();
	}
	const tabs = $derived([
		{ value: 'fields' as Tab, label: nt('nt2.tab.fields'), count: draft.fields.length },
		{ value: 'templates' as Tab, label: nt('nt2.tab.templates'), count: draft.kinds.length },
		{ value: 'style' as Tab, label: nt('nt2.tab.style') },
		...(wide ? [] : [{ value: 'preview' as Tab, label: tn('nt.preview') }])
	]);
</script>

{#snippet preview()}
	<section class="preview" aria-label={tn('nt.preview')}>
		<div class="pv-controls">
			{#if draft.kinds.length > 0}
				<div class="pv-kind">
					<Select label={tn('nt.preview.kind')} hideLabel bind:value={s.previewKind} options={draft.kinds.map((k) => ({ value: k.kind, label: k.kind }))} />
				</div>
			{/if}
			<SegmentedControl
				label={tn('nt.preview.side')}
				bind:value={s.previewSide}
				options={[
					{ value: 'front', label: tn('nt.preview.front') },
					{ value: 'back', label: tn('nt.preview.back') }
				]}
			/>
			<SegmentedControl
				label={ut('ui.theme')}
				bind:value={previewTheme}
				options={[
					{ value: 'light', label: ut('ui.theme.light'), icon: 'sun' },
					{ value: 'dark', label: ut('ui.theme.dark'), icon: 'moon' }
				]}
			/>
		</div>
		<div class="pv-card" data-theme={previewTheme}>
			{#if s.preview === null}
				<EmptyState art="info" title={tn('nt.kind.noDefault')} />
			{:else if s.preview.ok}
				<CardFrame rendered={s.preview.card} furigana={s.furigana} />
			{:else}
				<EmptyState tone="error" art="alert" title={tn('nt.preview.failed', { detail: '' }).replace(/[:\s]+$/, '')} body={s.preview.detail} />
			{/if}
		</div>
		<div class="pv-toggles">
			<Switch label={tn('nt.preview.furigana')} bind:checked={s.furigana} />
			<Switch label={tn('nt.preview.pitch')} bind:checked={s.pitch} />
		</div>
		{#if s.preview?.ok && s.preview.card.warnings.length > 0}
			<Banner tone="warning" title={tn('nt.preview.warnings')}>
				<ul class="warn">{#each [...new Set(s.preview.card.warnings)] as w (w)}<li>{warningText(w)}</li>{/each}</ul>
			</Banner>
		{:else if s.preview?.ok}
			<p class="hint">{tn('nt.preview.noWarnings')}</p>
		{/if}
		<details class="samples">
			<summary>{tn('nt.preview.samples')}</summary>
			<div class="sample-grid">
				{#each draft.fields as f (f.key)}
					<TextField label={f.label || f.key} value={s.samples[f.key] ?? ''} oninput={(e) => (s.samples[f.key] = (e.currentTarget as HTMLInputElement).value)} lang="ja" />
				{/each}
				<TextField label={tn('nt.preview.mnemonic')} bind:value={s.mnemonic} />
			</div>
		</details>
	</section>
{/snippet}

<form class="editor" class:wide onsubmit={submit} bind:this={form}>
	<div class="basics">
		<TextField label={tn('nt.name')} bind:value={draft.name} maxlength={100} />
		<Select
			label={tn('nt.baseType')}
			bind:value={draft.baseType}
			disabled={!isNew}
			options={BASE_TYPES.map((b) => ({ value: b, label: tn(`nt.base.${b}`) }))}
		/>
	</div>

	<div class="split">
		<div class="left">
			<Tabs label={nt('nt2.sections')} bind:value={tab} {tabs} />
			<div class="panel" role="tabpanel">
				{#if tab === 'fields'}
					<p class="hint">{tn('nt.field.keyHelp')}</p>
					<ul class="fields">
						{#each draft.fields as f, i (i)}
							<li>
								<div class="f-key"><TextField label={tn('nt.field.key')} bind:value={f.key} readonly={f.saved} spellcheck="false" /></div>
								<div class="f-label"><TextField label={tn('nt.field.label')} bind:value={f.label} /></div>
								<div class="f-kind">
									<Select label={tn('nt.field.kind')} bind:value={f.kind} options={FIELD_KINDS.map((k) => ({ value: k, label: tn(`nt.field.kind.${k}`) }))} />
								</div>
								<div class="f-del">
									{#if !f.saved}<IconButton icon="trash" label={tn('nt.field.remove')} onclick={() => s.removeField(i)} />{/if}
								</div>
							</li>
						{/each}
					</ul>
					<div><Button variant="tonal" icon="plus" onclick={() => s.addField()}>{tn('nt.field.add')}</Button></div>
				{:else if tab === 'templates'}
					<p class="hint">{tn('nt.kind.builtinHelp')}</p>
					{#each draft.kinds as k, i (k.kind)}
						<details class="kind" open={i === 0}>
							<summary><span class="kname">{k.kind}</span>{#if k.enabled}<span class="tag">{nt('nt2.default')}</span>{/if}</summary>
							<div class="kbody">
								<Switch label={tn('nt.kind.enabled')} bind:checked={k.enabled} />
								<TextArea label={tn('nt.kind.front')} code rows={5} spellcheck="false" bind:value={k.front} />
								<TextArea label={tn('nt.kind.back')} code rows={5} spellcheck="false" bind:value={k.back} />
								<div class="right"><Button size="sm" variant="quiet" icon="trash" onclick={() => draft.kinds.splice(i, 1)}>{tn('nt.kind.remove')}</Button></div>
							</div>
						</details>
					{/each}
					<div class="add-kind">
						<TextField label={tn('nt.kind.newName')} bind:value={s.newKind} spellcheck="false" />
						<Button variant="tonal" icon="plus" onclick={() => s.addKind()}>{tn('nt.kind.add')}</Button>
					</div>
				{:else if tab === 'style'}
					<TextArea label={tn('nt.css')} code rows={14} spellcheck="false" bind:value={draft.css} />
				{:else}
					{@render preview()}
				{/if}
			</div>
		</div>
		{#if wide}
			<aside class="right-pane">{@render preview()}</aside>
		{/if}
	</div>

	<div class="actions">
		<Button variant="primary" type="submit" loading={busy}>{submitLabel}</Button>
	</div>
</form>

<style>
	.editor {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
	}
	.basics {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 16rem), 1fr));
		gap: var(--kb-space-4);
		max-width: 44rem;
	}
	.split {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
		gap: var(--kb-space-6);
		align-items: start;
	}
	.wide .split {
		grid-template-columns: minmax(0, 1fr) minmax(20rem, 0.8fr);
	}
	.panel {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
		padding-top: var(--kb-space-4);
	}
	.hint {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.fields {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.fields li {
		display: grid;
		grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr) minmax(7rem, 0.8fr) 2.75rem;
		align-items: end;
		gap: var(--kb-space-2);
		padding: var(--kb-space-3);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface);
	}
	@media (max-width: 599.98px) {
		.fields li {
			grid-template-columns: minmax(0, 1fr) 2.75rem;
		}
		.f-key {
			grid-column: 1;
		}
		.f-label,
		.f-kind {
			grid-column: 1;
		}
		.f-del {
			grid-column: 2;
			grid-row: 1;
		}
	}
	.kind {
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-md);
		background: var(--kb-color-surface);
	}
	.kind summary {
		display: flex;
		align-items: center;
		gap: var(--kb-space-2);
		min-height: 3rem;
		padding: 0 var(--kb-space-4);
		cursor: pointer;
	}
	.kname {
		font: 600 0.9375rem/1.4 var(--kb-family-mono);
	}
	.tag {
		padding: 1px var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-primary-container);
		color: var(--kb-color-on-primary-container);
		font: var(--kb-type-caption);
	}
	.kbody {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
		padding: 0 var(--kb-space-4) var(--kb-space-4);
	}
	.right {
		display: flex;
		justify-content: flex-end;
	}
	.add-kind {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		align-items: end;
		gap: var(--kb-space-2);
	}
	.right-pane {
		position: sticky;
		top: var(--kb-space-6);
	}
	.preview {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
	}
	.pv-controls {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		align-items: center;
	}
	.pv-kind {
		flex: 1 1 8rem;
	}
	.pv-card {
		min-height: 14rem;
		padding: var(--kb-space-8) var(--kb-space-5);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-card-bg, var(--kb-color-surface));
		color: var(--kb-card-fg, var(--kb-color-text));
		box-shadow: var(--kb-elevation-1);
	}
	.pv-toggles {
		display: flex;
		flex-wrap: wrap;
		gap: 0 var(--kb-space-6);
	}
	.warn {
		margin: 0;
		padding-left: var(--kb-space-4);
	}
	.samples summary {
		min-height: var(--kb-size-touch-min);
		display: flex;
		align-items: center;
		font: var(--kb-type-label);
		cursor: pointer;
	}
	.sample-grid {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 12rem), 1fr));
		gap: var(--kb-space-3);
	}
	.actions {
		position: sticky;
		bottom: 0;
		display: flex;
		justify-content: flex-end;
		padding: var(--kb-space-3) 0;
		background: var(--kb-color-bg);
		border-top: 1px solid var(--kb-color-border);
	}
</style>
