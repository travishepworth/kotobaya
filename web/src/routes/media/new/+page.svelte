<script lang="ts">
	// Import media: source (files / URL / paste / jpdb deck), details, study-deck options; then live progress.
	import { onDestroy } from 'svelte';
	import Banner from '../../../lib/ui/Banner.svelte';
	import Button from '../../../lib/ui/Button.svelte';
	import EmptyState from '../../../lib/ui/EmptyState.svelte';
	import FileDrop from '../../../lib/ui/FileDrop.svelte';
	import Page from '../../../lib/ui/Page.svelte';
	import Panel from '../../../lib/ui/Panel.svelte';
	import ProgressBar from '../../../lib/ui/ProgressBar.svelte';
	import Select from '../../../lib/ui/Select.svelte';
	import Switch from '../../../lib/ui/Switch.svelte';
	import Tabs from '../../../lib/ui/Tabs.svelte';
	import TextArea from '../../../lib/ui/TextArea.svelte';
	import TextField from '../../../lib/ui/TextField.svelte';
	import { tm, type MediaMessageKey } from '../../../lib/media/messages';
	import { IMPORT_FORMATS, IMPORT_TABS, isRunning, MEDIA_KINDS, MediaNewPage } from '../../../lib/state/media.svelte';
	import { mx } from '../messages';

	const vm = new MediaNewPage();
	onDestroy(() => vm.dispose());

	let priorityText = $state(String(vm.priority));
	$effect(() => {
		const n = Number(priorityText);
		if (Number.isFinite(n)) vm.priority = n;
	});
</script>

<svelte:head><title>{tm('new.title')} · Kotobaya</title></svelte:head>

<Page title={tm('new.title')} back={{ href: '/media', label: mx('mx.library') }} width="narrow">
	{#if vm.job === null}
		<form
			class="form"
			onsubmit={(e) => {
				e.preventDefault();
				void vm.start();
			}}
		>
			<Tabs label={tm('new.tabs')} bind:value={vm.tab} tabs={IMPORT_TABS.map((t) => ({ value: t, label: tm(`new.tab.${t}` as MediaMessageKey) }))} />

			<Panel title={mx('mx.source')}>
				<div class="stack">
					{#if vm.tab === 'files'}
						<FileDrop
							title={mx('mx.drop')}
							hint={mx('mx.dropHint')}
							multiple
							icon="media"
							chosen={vm.files.length ? mx('mx.chosen', { n: vm.files.length }) : undefined}
							onfiles={(f) => vm.setFiles(f)}
						/>
						{#each vm.files as f, i (i)}
							<label class="audio">
								<span>{tm('new.audio', { name: f.name })}</span>
								<input type="file" accept="audio/*" onchange={(e) => vm.setAudio(i, e.currentTarget.files?.[0])} />
							</label>
						{/each}
						<div class="two">
							<Select label={tm('new.format')} bind:value={vm.format} options={IMPORT_FORMATS.map((f) => ({ value: f, label: f === 'auto' ? tm('new.format.auto') : f }))} />
							<Select
								label={tm('new.ruby')}
								bind:value={vm.ruby}
								options={[
									{ value: 'strip', label: tm('new.ruby.strip') },
									{ value: 'hint', label: tm('new.ruby.hint') }
								]}
							/>
						</div>
						<Switch label={tm('new.cutAudio')} bind:checked={vm.cutAudio} />
					{:else if vm.tab === 'url'}
						<TextField label={tm('new.url')} type="url" bind:value={vm.url} required />
					{:else if vm.tab === 'paste'}
						<TextArea label={tm('new.text')} lang="ja" rows={10} maxlength={1000000} bind:value={vm.text} />
					{:else}
						<TextField
							label={tm('new.jpdbId')}
							bind:value={vm.jpdbId}
							required
							error={vm.jpdbId.trim() !== '' && vm.deckId === null ? tm('new.jpdbIdInvalid') : undefined}
						/>
					{/if}
				</div>
			</Panel>

			<Panel title={mx('mx.details')}>
				<div class="two">
					<TextField label={tm('new.mediaTitle')} hint={vm.tab === 'jpdb' ? tm('new.jpdbTitle') : undefined} lang="ja" bind:value={vm.title} required={vm.tab !== 'jpdb'} />
					<Select label={tm('new.kind')} bind:value={vm.kind} options={MEDIA_KINDS.map((k) => ({ value: k, label: tm(`med.kind.${k}` as MediaMessageKey) }))} />
				</div>
			</Panel>

			<Panel title={mx('mx.deck')}>
				<div class="stack">
					<div class="two">
						<TextField label={tm('new.priority')} inputmode="numeric" bind:value={priorityText} />
						<TextField label={tm('new.perDay')} inputmode="numeric" bind:value={vm.perDay} />
					</div>
					<Switch label={tm('new.enabled')} bind:checked={vm.enabled} />
				</div>
			</Panel>

			{#if vm.upload}
				<div role="status">
					<ProgressBar value={vm.upload.done} max={vm.upload.total} label={tm('new.uploading', { done: vm.upload.done, total: vm.upload.total })} showCount />
				</div>
			{/if}
			<div class="actions">
				<Button variant="primary" size="lg" type="submit" icon="import" disabled={!vm.canStart} loading={vm.busy}>
					{vm.failed ? tm('new.retry') : tm('new.start')}
				</Button>
			</div>
		</form>
	{:else if isRunning(vm.job)}
		<Panel>
			<div class="running" role="status">
				<p class="big kb-num">{Math.round(vm.job.progress * 100)}%</p>
				<ProgressBar value={Math.round(vm.job.progress * 100)} max={100} label={tm('new.title')} />
				{#if vm.job.progressMessage}<p class="muted">{vm.job.progressMessage}</p>{/if}
			</div>
		</Panel>
	{:else if vm.job.status === 'succeeded'}
		<EmptyState size="page" art="success" title={tm('new.done')} body={vm.unmappedCount > 0 ? tm('new.unmapped', { count: vm.unmappedCount }) : undefined}>
			{#snippet action()}
				<div class="row">
					{#if vm.mediaId}<Button variant="primary" href="/media/{vm.mediaId}">{tm('new.open')}</Button>{/if}
					{#if vm.unmappedCount > 0}<Button variant="outline" href="/import/mapping">{tm('new.unmappedLink')}</Button>{/if}
					<Button variant="quiet" onclick={() => vm.reset()}>{tm('new.another')}</Button>
				</div>
			{/snippet}
		</EmptyState>
	{:else}
		<Banner tone="error">
			{vm.job.status === 'failed' ? tm('new.failed', { error: vm.job.error ?? tm('new.noError') }) : tm('new.cancelled')}
			{#snippet action()}<Button size="sm" variant="outline" onclick={() => vm.reset()}>{tm('new.another')}</Button>{/snippet}
		</Banner>
	{/if}
</Page>

<style>
	.form {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
	}
	.stack {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-4);
	}
	.two {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
		gap: var(--kb-space-3);
	}
	.audio {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		font: var(--kb-type-body-sm);
	}
	.audio input {
		min-height: var(--kb-size-touch-min);
	}
	.actions {
		display: flex;
		justify-content: flex-end;
	}
	.running {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
	}
	.big {
		font: var(--kb-type-display);
	}
	.muted {
		color: var(--kb-color-text-muted);
	}
	.row {
		display: flex;
		flex-wrap: wrap;
		justify-content: center;
		gap: var(--kb-space-2);
	}
</style>
