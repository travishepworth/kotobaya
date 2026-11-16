<script lang="ts">
	// Settings › "This device" (Android shell only): sync, offline dictionary, storage, notifications, UnifiedPush,
	// AnkiConnect, Takoboto, Unpair. Each group is shown only when the shell lists its capability.
	import { goto } from '$app/navigation';
	import Banner from '../../lib/ui/Banner.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import Dialog from '../../lib/ui/Dialog.svelte';
	import ProgressBar from '../../lib/ui/ProgressBar.svelte';
	import Select from '../../lib/ui/Select.svelte';
	import Switch from '../../lib/ui/Switch.svelte';
	import { ut } from '../../lib/ui/messages';
	import { sh } from '../../lib/ui/shellMessages';
	import { formatBytes } from '../../lib/ui/syncView';
	import { DeviceState, syncStatus, type DictScope } from '../../lib/state/device.svelte';
	import { refreshSession } from '../../lib/state/session.svelte';
	import { has, shell } from '../../lib/state/shell.svelte';
	import SyncSection from './SyncSection.svelte';

	const vm = new DeviceState();
	$effect(() => {
		if (shell.present) void vm.load();
	});

	const info = $derived(shell.info);
	const dict = $derived(vm.dict);
	const installing = $derived(dict !== null && dict.state !== 'idle');
	const pending = $derived(syncStatus.value?.pendingOps ?? 0);

	let scope = $state<DictScope>('top60k');
	let scopeSeeded = false;
	$effect(() => {
		// Start from the installed scope once it is known.
		if (!scopeSeeded && dict) {
			scopeSeeded = true;
			if (dict.installed?.scope === 'all') scope = 'all';
		}
	});
	const sizeOf = (s: DictScope): string => (vm.sizes[s] !== undefined ? formatBytes(vm.sizes[s]) : '');
	const sized = (name: string, size: string): string => (size ? sh('dev.dictSized', { name, size }) : name);
	const scopeOptions = $derived([
		{ value: 'top60k' as const, label: sized(sh('dev.dictTop'), sizeOf('top60k')) },
		{ value: 'all' as const, label: sized(sh('dev.dictAll'), sizeOf('all')) }
	]);
	const outdated = $derived(
		dict?.installed != null && dict.serverReleaseId != null && dict.installed.releaseId < dict.serverReleaseId
	);
	const stateLabel = $derived(
		dict?.state === 'checking'
			? sh('dev.dictChecking')
			: dict?.state === 'downloading'
				? sh('dev.dictDownloading')
				: dict?.state === 'verifying'
					? sh('dev.dictVerifying')
					: sh('dev.dictApplying')
	);
	let dictBusy = $state(false);
	async function install(): Promise<void> {
		dictBusy = true;
		await vm.installDict(scope);
		dictBusy = false;
	}

	/** Options for a numeric setting that always include the current value. */
	function numOptions(values: number[], current: number, label: (n: number) => string): { value: number; label: string }[] {
		const all = values.includes(current) ? values : [...values, current].sort((a, b) => a - b);
		return all.map((n) => ({ value: n, label: label(n) }));
	}
	const budgetOptions = $derived(
		numOptions([256, 512, 1024, 2048, 4096], vm.storage?.budgetMb ?? 1024, (n) => (n >= 1024 ? `${n / 1024} GB` : `${n} MB`))
	);
	const prefetchOptions = $derived(numOptions([0, 1, 3, 7], vm.storage?.prefetchDays ?? 3, (n) => sh('dev.days', { n })));
	const thresholdOptions = $derived(numOptions([5, 10, 20, 50], vm.notifications?.nudgeThreshold ?? 20, (n) => String(n)));

	let unpairing = $state(false);
	let unpairError = $state(false);
	async function unpair(): Promise<void> {
		unpairError = false;
		if (await vm.unpair(pending > 0)) {
			unpairing = false;
			await refreshSession(); // 401 now: the guard leads to /login, which is the Pair screen
			await goto('/login', { replaceState: true });
		} else unpairError = true;
	}
</script>

{#if !shell.present || !info}
	<p class="hint">{sh('dev.loadFailed')}</p>
{:else}
	<div class="groups">
		{#if vm.failed}<Banner tone="warning">{sh('dev.loadFailed')}</Banner>{/if}

		<dl class="rows">
			<div><dt>{sh('dev.server')}</dt><dd>{info.serverUrl ?? '–'}</dd></div>
			<div><dt>{sh('dev.name')}</dt><dd>{info.deviceName ?? '–'}</dd></div>
			<div><dt>{sh('dev.version')}</dt><dd>{info.shellVersion}</dd></div>
		</dl>

		{#if has('sync')}
			<section aria-labelledby="dev-sync">
				<h3 id="dev-sync">{sh('dev.sync')}</h3>
				<SyncSection />
			</section>
		{/if}

		{#if has('dict_install') && dict}
			<section aria-labelledby="dev-dict">
				<h3 id="dev-dict">{sh('dev.dict')}</h3>
				<p class="hint">
					{#if dict.installed}
						{sh('dev.dictInstalled', {
							scope: dict.installed.scope === 'all' ? sh('dev.dictAll') : sh('dev.dictTop'),
							release: dict.installed.releaseId,
							size: formatBytes(dict.installed.bytes)
						})}
					{:else}
						{sh('dev.dictNone')}
					{/if}
				</p>
				{#if dict.gapFill}<p class="hint">{sh('dev.dictGap', { cached: dict.gapFill.cached, missing: dict.gapFill.missing })}</p>{/if}
				{#if outdated}<p class="hint">{sh('dev.dictNewer')}</p>{/if}
				{#if installing}
					<ProgressBar
						value={dict.doneBytes}
						max={dict.totalBytes}
						label={sh('dev.dictProgress', { state: stateLabel, done: formatBytes(dict.doneBytes), total: formatBytes(dict.totalBytes) })}
					/>
					<p class="hint">{sh('dev.dictProgress', { state: stateLabel, done: formatBytes(dict.doneBytes), total: formatBytes(dict.totalBytes) })}</p>
				{:else}
					<Select label={sh('dev.dictScope')} options={scopeOptions} bind:value={scope} />
					<div class="act">
						<Button variant="tonal" icon="sync" loading={dictBusy} disabled={!shell.online} onclick={install}>
							{dict.installed ? sh('dev.dictUpdate') : sh('dev.dictInstall')}
						</Button>
						{#if !shell.online}<span class="hint">{sh('dev.needsOnline')}</span>{/if}
					</div>
					{#if dict.lastResult && dict.lastResult !== 'installed'}
						<Banner tone={dict.lastResult === 'failed' || dict.lastResult === 'insufficient_space' ? 'error' : 'info'}>
							{sh(`dev.dictResult.${dict.lastResult}`)}
						</Banner>
					{/if}
				{/if}
			</section>
		{/if}

		{#if has('offline_storage') && vm.storage}
			{@const st = vm.storage}
			<section aria-labelledby="dev-storage">
				<h3 id="dev-storage">{sh('dev.storage')}</h3>
				<dl class="rows">
					<div><dt>{sh('dev.storageAudio')}</dt><dd class="kb-num">{formatBytes(st.cardAudioBytes)}</dd></div>
					<div><dt>{sh('dev.storageMedia')}</dt><dd class="kb-num">{formatBytes(st.mediaBytes)}</dd></div>
					<div><dt>{sh('dev.storageCovers')}</dt><dd class="kb-num">{formatBytes(st.coverBytes)}</dd></div>
					<div><dt>{sh('dev.storageDict')}</dt><dd class="kb-num">{formatBytes(st.dictionaryBytes)}</dd></div>
					<div class="total"><dt>{sh('dev.storageTotal')}</dt><dd class="kb-num">{formatBytes(st.totalBytes)}</dd></div>
				</dl>
				<Select label={sh('dev.storageBudget')} options={budgetOptions} value={st.budgetMb} onchange={(v) => vm.patchStorage({ budgetMb: v })} />
				<Select label={sh('dev.storagePrefetch')} options={prefetchOptions} value={st.prefetchDays} onchange={(v) => vm.patchStorage({ prefetchDays: v })} />
				<Switch label={sh('dev.storageMetered')} checked={st.allowMetered} onchange={(v) => vm.patchStorage({ allowMetered: v })} />
				<div class="act"><Button variant="quiet" icon="trash" onclick={() => vm.patchStorage({ clearCache: true })}>{sh('dev.storageClear')}</Button></div>
			</section>
		{/if}

		{#if has('notifications') && vm.notifications}
			{@const n = vm.notifications}
			<section aria-labelledby="dev-notif">
				<h3 id="dev-notif">{sh('dev.notif')}</h3>
				<p class="hint">
					{n.permission === 'granted' ? sh('dev.notifGranted') : n.permission === 'denied' ? sh('dev.notifDenied') : sh('dev.notifNotRequested')}
				</p>
				{#if n.permission !== 'granted' && n.canRequest}
					<div class="act"><Button variant="tonal" onclick={() => vm.requestNotifications()}>{sh('dev.notifAllow')}</Button></div>
				{/if}
				<Switch
					label={sh('dev.nudges')}
					description={sh('dev.nudgesBody')}
					checked={n.nudgesEnabled}
					onchange={(v) => vm.patchNotifications({ nudgesEnabled: v })}
				/>
				{#if n.nudgesEnabled}
					<Select label={sh('dev.nudgeThreshold')} options={thresholdOptions} value={n.nudgeThreshold} onchange={(v) => vm.patchNotifications({ nudgeThreshold: v })} />
				{/if}
			</section>
		{/if}

		{#if has('push') && vm.push}
			{@const p = vm.push}
			<section aria-labelledby="dev-push">
				<h3 id="dev-push">{sh('dev.push')}</h3>
				<Switch
					label={sh('dev.push')}
					description={sh('dev.pushBody')}
					checked={p.state !== 'off'}
					disabled={p.state === 'no_distributor'}
					onchange={(v) => vm.setPush(v)}
				/>
				<p class="hint">
					{p.state === 'on' ? sh('dev.pushOn') : p.state === 'waiting' ? sh('dev.pushWaiting') : p.state === 'no_distributor' ? sh('dev.pushNoDistributor') : sh('dev.pushOff')}
				</p>
			</section>
		{/if}

		{#if has('ankiconnect') && vm.anki}
			{@const a = vm.anki}
			<section aria-labelledby="dev-anki">
				<h3 id="dev-anki">{sh('dev.anki')}</h3>
				<Switch label={sh('dev.anki')} description={sh('dev.ankiBody')} checked={a.enabled} onchange={(v) => vm.setAnki(v)} />
				<p class="hint">
					{a.status === 'running' ? sh('dev.ankiRunning', { port: a.port }) : a.status === 'failed' ? (a.statusMessage ?? sh('dev.ankiFailed')) : sh('dev.ankiStopped')}
				</p>
			</section>
		{/if}

		{#if has('takoboto')}
			<section aria-labelledby="dev-tako">
				<h3 id="dev-tako">{sh('dev.takoboto')}</h3>
				<p class="hint">{sh('dev.takobotoOn')}. {sh('dev.takobotoBody')}</p>
			</section>
		{/if}

		{#if has('pairing')}
			<section aria-labelledby="dev-unpair">
				<h3 id="dev-unpair">{sh('dev.unpair')}</h3>
				<p class="hint">{sh('dev.unpairBody')}</p>
				<div class="act"><Button variant="danger" onclick={() => (unpairing = true)}>{sh('dev.unpair')}</Button></div>
			</section>
		{/if}
	</div>

	<Dialog bind:open={unpairing} title={sh('dev.unpairTitle')}>
		<p>{sh('dev.unpairBody')}</p>
		{#if pending > 0}<Banner tone="warning">{sh('dev.unpairPending', { n: pending })}</Banner>{/if}
		{#if unpairError}<Banner tone="error">{sh('dev.loadFailed')}</Banner>{/if}
		{#snippet actions()}
			<Button variant="quiet" onclick={() => (unpairing = false)}>{ut('app.cancel')}</Button>
			<Button variant="danger" loading={vm.busy} onclick={unpair}>{sh('dev.unpairConfirm')}</Button>
		{/snippet}
	</Dialog>
{/if}

<style>
	.groups {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-6);
	}
	section {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
		min-width: 0;
	}
	h3 {
		margin: 0;
		font: var(--kb-type-title-sm);
	}
	.rows {
		display: grid;
		gap: var(--kb-space-2);
		margin: 0;
	}
	.rows div {
		display: flex;
		justify-content: space-between;
		gap: var(--kb-space-3);
		min-width: 0;
	}
	.total {
		padding-top: var(--kb-space-2);
		border-top: 1px solid var(--kb-color-border);
		font-weight: 600;
	}
	dt {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	dd {
		margin: 0;
		min-width: 0;
		text-align: end;
		overflow-wrap: anywhere;
	}
	.act {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		flex-wrap: wrap;
	}
	.hint {
		margin: 0;
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
</style>
