<script lang="ts">
	import { onDestroy } from 'svelte';
	import { tm } from '../../../lib/media/messages';
	import type { MediaMessageKey } from '../../../lib/media/messages';
	import { IMPORT_FORMATS, IMPORT_TABS, isRunning, MEDIA_KINDS, MediaNewPage } from '../../../lib/state/media.svelte';

	const vm = new MediaNewPage();
	onDestroy(() => vm.dispose());
</script>

<h1>{tm('new.title')}</h1>

{#if vm.job === null}
	<div role="tablist" aria-label={tm('new.tabs')}>
		{#each IMPORT_TABS as tb (tb)}
			<button
				role="tab"
				type="button"
				class="kb-btn kb-btn-quiet"
				aria-selected={vm.tab === tb}
				disabled={vm.busy}
				onclick={() => (vm.tab = tb)}>{tm(`new.tab.${tb}` as MediaMessageKey)}</button
			>
		{/each}
	</div>
	<form
		onsubmit={(e) => {
			e.preventDefault();
			void vm.start();
		}}
	>
		<p>
			<label
				>{tm('new.mediaTitle')}
				<input lang="ja" bind:value={vm.title} required={vm.tab !== 'jpdb'} /></label
			>
			{#if vm.tab === 'jpdb'}<small>{tm('new.jpdbTitle')}</small>{/if}
		</p>
		<p>
			<label
				>{tm('new.kind')}
				<select bind:value={vm.kind}>
					{#each MEDIA_KINDS as k (k)}
						<option value={k}>{tm(`med.kind.${k}` as MediaMessageKey)}</option>
					{/each}
				</select></label
			>
		</p>

		{#if vm.tab === 'files'}
			<p>
				<label
					>{tm('new.files')}
					<input
						type="file"
						multiple
						onchange={(e) => vm.setFiles(e.currentTarget.files)}
					/></label
				>
			</p>
			<p>
				<label
					>{tm('new.format')}
					<select bind:value={vm.format}>
						{#each IMPORT_FORMATS as f (f)}
							<option value={f}>{f === 'auto' ? tm('new.format.auto') : f}</option>
						{/each}
					</select></label
				>
			</p>
			<p>
				<label
					>{tm('new.ruby')}
					<select bind:value={vm.ruby}>
						<option value="strip">{tm('new.ruby.strip')}</option>
						<option value="hint">{tm('new.ruby.hint')}</option>
					</select></label
				>
			</p>
			{#each vm.files as f, i (i)}
				<p>
					<label
						>{tm('new.audio', { name: f.name })}
						<input
							type="file"
							accept="audio/*"
							onchange={(e) => vm.setAudio(i, e.currentTarget.files?.[0])}
						/></label
					>
				</p>
			{/each}
			<p>
				<label><input type="checkbox" bind:checked={vm.cutAudio} /> {tm('new.cutAudio')}</label>
			</p>
		{:else if vm.tab === 'url'}
			<p><label>{tm('new.url')} <input type="url" bind:value={vm.url} required /></label></p>
		{:else if vm.tab === 'paste'}
			<p>
				<label
					>{tm('new.text')}
					<textarea lang="ja" rows="10" maxlength="1000000" bind:value={vm.text}></textarea></label
				>
			</p>
		{:else}
			<p><label>{tm('new.jpdbId')} <input bind:value={vm.jpdbId} required /></label></p>
			{#if vm.jpdbId.trim() !== '' && vm.deckId === null}
				<p role="alert">{tm('new.jpdbIdInvalid')}</p>
			{/if}
		{/if}

		<fieldset>
			<legend>{tm('new.deckOptions')}</legend>
			<p>
				<label
					>{tm('new.priority')}
					<input type="number" min="0" max="9" bind:value={vm.priority} /></label
				>
			</p>
			<p>
				<label>{tm('new.perDay')} <input type="number" min="0" bind:value={vm.perDay} /></label>
			</p>
			<p><label><input type="checkbox" bind:checked={vm.enabled} /> {tm('new.enabled')}</label></p>
		</fieldset>

		{#if vm.upload}
			<p role="status">{tm('new.uploading', { done: vm.upload.done, total: vm.upload.total })}</p>
		{/if}
		<button class="kb-btn" type="submit" disabled={!vm.canStart || vm.busy}>
			{vm.failed ? tm('new.retry') : tm('new.start')}
		</button>
	</form>
{:else if isRunning(vm.job)}
	<p role="status">
		{tm('new.progress', { status: vm.job.status, percent: Math.round(vm.job.progress * 100) })}
	</p>
	<progress max="1" value={vm.job.progress}></progress>
	{#if vm.job.progressMessage}<p>{vm.job.progressMessage}</p>{/if}
{:else if vm.job.status === 'succeeded'}
	<p role="status">{tm('new.done')}</p>
	{#if vm.unmappedCount > 0}
		<p>
			{tm('new.unmapped', { count: vm.unmappedCount })}
			<a href="/import/mapping">{tm('new.unmappedLink')}</a>
		</p>
	{/if}
	<p>
		{#if vm.mediaId}<a class="kb-btn" href={`/media/${vm.mediaId}`}>{tm('new.open')}</a>{/if}
		<a href="/media">{tm('new.library')}</a>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.reset()}>{tm('new.another')}</button>
	</p>
{:else}
	<p role="alert">
		{vm.job.status === 'failed'
			? tm('new.failed', { error: vm.job.error ?? tm('new.noError') })
			: tm('new.cancelled')}
	</p>
	<p><button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.reset()}>{tm('new.another')}</button></p>
{/if}
