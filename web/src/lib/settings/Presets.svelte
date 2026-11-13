<script lang="ts">
	import { DAYS, DEFAULT_PRESET_ID, PresetsState, type Preset } from '../state/settings.svelte';
	import { ts } from './messages';

	const vm = new PresetsState();

	$effect(() => {
		void vm.load();
	});

	function save(p: Preset, e: SubmitEvent): void {
		e.preventDefault();
		void vm.save(p);
	}

	function remove(p: Preset): void {
		if (!confirm(ts('presets.confirmDelete', { name: p.name }))) return;
		void vm.remove(p);
	}
</script>

<section>
	<h2>{ts('presets.title')}</h2>
	{#each vm.presets as p (p.id)}
		{@const d = vm.drafts[p.id]}
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
					<button class="kb-btn" type="submit" disabled={vm.busy}>{ts('settings.save')}</button>
					<button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.optimize(p)}>
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
	<button class="kb-btn" type="button" disabled={vm.busy} onclick={() => vm.add()}>{ts('presets.add')}</button>
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
