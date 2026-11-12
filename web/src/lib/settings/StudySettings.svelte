<script lang="ts">
	import { pushToast } from '../toasts.svelte';
	import { settingsApi } from './api';
	import { STUDY_SETTINGS, type SettingDef } from './keys';
	import { ts } from './messages';

	/** Draft values as form strings / booleans, keyed by setting key. */
	let draft = $state<Record<string, string | boolean>>({});
	let stored = $state<Record<string, unknown>>({});
	let loaded = $state(false);
	let busy = $state(false);

	function toDraft(def: SettingDef, v: unknown): string | boolean {
		const value = v === undefined || v === null ? def.default : v;
		return def.type === 'bool' ? value === true : String(value);
	}

	function apply(items: { key: string; value: unknown }[]): void {
		const map: Record<string, unknown> = {};
		for (const i of items) map[i.key] = i.value;
		stored = map;
		const d: Record<string, string | boolean> = {};
		for (const def of STUDY_SETTINGS) d[def.key] = toDraft(def, map[def.key]);
		draft = d;
	}

	async function load(): Promise<void> {
		try {
			apply((await settingsApi.listSettings()).items);
			loaded = true;
		} catch {
			/* error toast already shown by the client */
		}
	}
	$effect(() => {
		void load();
	});

	/** The value to send for a def, or undefined when unchanged; throws a message on bad input. */
	function toValue(def: SettingDef): unknown {
		const raw = draft[def.key];
		switch (def.type) {
			case 'bool':
				return raw === true;
			case 'string':
				return String(raw).trim();
			case 'buttons':
				return Number(raw) === 5 ? 5 : 4;
			case 'int': {
				const s = String(raw).trim();
				const n = Number(s);
				const bad =
					!/^-?\d+$/.test(s) ||
					(def.min !== undefined && n < def.min) ||
					(def.max !== undefined && n > def.max);
				if (bad) throw new Error(ts('settings.invalidNumber', { field: ts(def.label) }));
				return n;
			}
		}
	}

	async function save(e: SubmitEvent): Promise<void> {
		e.preventDefault();
		const values: Record<string, unknown> = {};
		try {
			for (const def of STUDY_SETTINGS) {
				const v = toValue(def);
				const before = stored[def.key] ?? def.default;
				if (v !== before) values[def.key] = v;
			}
		} catch (err) {
			pushToast(err instanceof Error ? err.message : String(err), 'error');
			return;
		}
		if (Object.keys(values).length === 0) return;
		busy = true;
		try {
			apply((await settingsApi.updateSettings({ settingsPatch: { values } })).items);
			pushToast(ts('settings.saved'), 'info');
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}

	async function resetKey(def: SettingDef): Promise<void> {
		busy = true;
		try {
			apply((await settingsApi.updateSettings({ settingsPatch: { values: { [def.key]: null } } })).items);
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}
</script>

<section>
	<h2>{ts('settings.study')}</h2>
	{#if !loaded}
		<p>{ts('settings.loading')}</p>
	{:else}
		<form onsubmit={save}>
			{#each STUDY_SETTINGS as def (def.key)}
				<div class="row">
					{#if def.type === 'bool'}
						<label>
							<input type="checkbox" bind:checked={draft[def.key] as boolean} />
							{ts(def.label)}
						</label>
					{:else if def.type === 'buttons'}
						<label>
							{ts(def.label)}
							<select bind:value={draft[def.key] as string}>
								<option value="4">{ts('settings.buttons4')}</option>
								<option value="5">{ts('settings.buttons5')}</option>
							</select>
						</label>
					{:else}
						<label>
							{ts(def.label)}
							<input
								type="text"
								inputmode={def.type === 'int' ? 'numeric' : 'text'}
								bind:value={draft[def.key] as string}
							/>
						</label>
					{/if}
					{#if stored[def.key] !== undefined && stored[def.key] !== null}
						<button class="kb-btn kb-btn-quiet" type="button" disabled={busy} onclick={() => resetKey(def)}>
							{ts('settings.reset')}
						</button>
					{/if}
				</div>
			{/each}
			<button class="kb-btn" type="submit" disabled={busy}>{ts('settings.save')}</button>
		</form>
	{/if}
</section>

<style>
	.row {
		display: flex;
		align-items: center;
		gap: var(--kb-space-3);
		margin-bottom: var(--kb-space-2);
	}
	label {
		display: flex;
		align-items: center;
		gap: var(--kb-space-2);
		flex: 1;
	}
</style>
