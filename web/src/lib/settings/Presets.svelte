<script lang="ts">
	import type { Preset } from '../api/generated';
	import { pushToast } from '../toasts.svelte';
	import { presetsApi } from './api';
	import { parseSteps } from './keys';
	import { ts, type SettingsMessageKey } from './messages';

	const DEFAULT_PRESET_ID = '00000000-0000-7000-8000-000000000010';
	const DAYS: SettingsMessageKey[] = ['day.1', 'day.2', 'day.3', 'day.4', 'day.5', 'day.6', 'day.7'];

	interface Draft {
		name: string;
		retention: string;
		maxInterval: string;
		learning: string;
		relearning: string;
		fuzz: boolean;
		easyDays: string[];
	}

	let presets = $state<Preset[]>([]);
	let drafts = $state<Record<string, Draft>>({});
	let busy = $state(false);

	function toDraft(p: Preset): Draft {
		return {
			name: p.name,
			retention: String(p.desiredRetention),
			maxInterval: String(p.maximumIntervalDays),
			learning: p.learningStepsS.join(', '),
			relearning: p.relearningStepsS.join(', '),
			fuzz: p.fuzz,
			easyDays: p.easyDays.map(String)
		};
	}

	async function load(): Promise<void> {
		try {
			presets = (await presetsApi.listPresets({})).items;
			drafts = Object.fromEntries(presets.map((p) => [p.id, toDraft(p)]));
		} catch {
			/* toast shown */
		}
	}
	$effect(() => {
		void load();
	});

	async function save(p: Preset, e: SubmitEvent): Promise<void> {
		e.preventDefault();
		const d = drafts[p.id];
		if (!d) return;
		const learning = parseSteps(d.learning);
		const relearning = parseSteps(d.relearning);
		if (!learning || !relearning) {
			pushToast(ts('presets.invalidSteps'), 'error');
			return;
		}
		busy = true;
		try {
			await presetsApi.updatePreset({
				presetId: p.id,
				presetPatch: {
					name: d.name.trim(),
					desiredRetention: Number(d.retention),
					maximumIntervalDays: Number(d.maxInterval),
					learningStepsS: learning,
					relearningStepsS: relearning,
					fuzz: d.fuzz,
					easyDays: d.easyDays.map(Number)
				}
			});
			pushToast(ts('settings.saved'), 'info');
			await load();
		} catch {
			/* 422 etc. shown as toast */
		} finally {
			busy = false;
		}
	}

	async function add(): Promise<void> {
		busy = true;
		try {
			await presetsApi.createPreset({ presetCreate: { name: ts('presets.newName') } });
			await load();
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}

	async function remove(p: Preset): Promise<void> {
		if (!confirm(ts('presets.confirmDelete', { name: p.name }))) return;
		try {
			await presetsApi.deletePreset({ presetId: p.id });
			await load();
		} catch {
			/* toast shown */
		}
	}

	async function optimize(p: Preset): Promise<void> {
		try {
			await presetsApi.optimizePreset({ presetId: p.id });
			pushToast(ts('presets.optimizeQueued'), 'info');
		} catch {
			/* toast shown */
		}
	}
</script>

<section>
	<h2>{ts('presets.title')}</h2>
	{#each presets as p (p.id)}
		{@const d = drafts[p.id]}
		{#if d}
			<form class="preset" onsubmit={(e) => save(p, e)}>
				<h3>
					{p.name}
					{#if p.id === DEFAULT_PRESET_ID}<small>({ts('presets.builtin')})</small>{/if}
					<small>{ts('presets.weightsVersion', { n: p.weightsVersion })}</small>
				</h3>
				<label>{ts('presets.name')} <input type="text" bind:value={d.name} /></label>
				<label>{ts('presets.retention')} <input type="text" inputmode="decimal" bind:value={d.retention} /></label>
				<label>{ts('presets.maxInterval')} <input type="text" inputmode="numeric" bind:value={d.maxInterval} /></label>
				<label>{ts('presets.learningSteps')} <input type="text" bind:value={d.learning} /></label>
				<label>{ts('presets.relearningSteps')} <input type="text" bind:value={d.relearning} /></label>
				<label><input type="checkbox" bind:checked={d.fuzz} /> {ts('presets.fuzz')}</label>
				<fieldset>
					<legend>{ts('presets.easyDays')}</legend>
					{#each DAYS as day, i (day)}
						<label class="day">
							{ts(day)}
							<input type="text" inputmode="decimal" size="3" bind:value={d.easyDays[i]} />
						</label>
					{/each}
				</fieldset>
				<div class="actions">
					<button class="kb-btn" type="submit" disabled={busy}>{ts('settings.save')}</button>
					<button class="kb-btn kb-btn-quiet" type="button" onclick={() => optimize(p)}>
						{ts('presets.optimize')}
					</button>
					{#if p.id !== DEFAULT_PRESET_ID}
						<button class="kb-btn kb-btn-quiet" type="button" onclick={() => remove(p)}>
							{ts('presets.delete')}
						</button>
					{/if}
				</div>
			</form>
		{/if}
	{/each}
	<button class="kb-btn" type="button" disabled={busy} onclick={add}>{ts('presets.add')}</button>
</section>

<style>
	.preset {
		display: grid;
		gap: var(--kb-space-2);
		padding: var(--kb-space-3);
		margin-bottom: var(--kb-space-3);
		background: var(--kb-surface);
		border: 1px solid var(--kb-border);
		border-radius: var(--kb-radius);
	}
	label {
		display: flex;
		gap: var(--kb-space-2);
		align-items: center;
	}
	.day {
		display: inline-flex;
		margin-right: var(--kb-space-3);
	}
	.actions {
		display: flex;
		gap: var(--kb-space-2);
	}
	small {
		color: var(--kb-text-muted);
		font-weight: 400;
	}
</style>
