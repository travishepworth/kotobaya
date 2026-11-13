<script lang="ts">
	import { STUDY_SETTINGS, StudySettingsState } from '../state/settings.svelte';
	import { ts } from './messages';

	const vm = new StudySettingsState();

	$effect(() => {
		void vm.load();
	});

	function save(e: SubmitEvent): void {
		e.preventDefault();
		void vm.save();
	}
</script>

<section>
	<h2>{ts('settings.study')}</h2>
	{#if !vm.loaded}
		<p>{ts('settings.loading')}</p>
	{:else}
		<form onsubmit={save}>
			{#each STUDY_SETTINGS as def (def.key)}
				<div class="row">
					{#if def.type === 'bool'}
						<label>
							<input type="checkbox" bind:checked={vm.draft[def.key] as boolean} />
							{ts(def.label)}
						</label>
					{:else if def.type === 'buttons'}
						<label>
							{ts(def.label)}
							<select bind:value={vm.draft[def.key] as string}>
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
								bind:value={vm.draft[def.key] as string}
							/>
						</label>
					{/if}
					{#if vm.stored[def.key] !== undefined && vm.stored[def.key] !== null}
						<button class="kb-btn kb-btn-quiet" type="button" disabled={vm.busy} onclick={() => vm.resetKey(def)}>
							{ts('settings.reset')}
						</button>
					{/if}
				</div>
			{/each}
			<button class="kb-btn" type="submit" disabled={vm.busy}>{ts('settings.save')}</button>
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
