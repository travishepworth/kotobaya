<script lang="ts">
	// Study settings as grouped rows with inline controls; one Save for the group.
	import Button from '../../lib/ui/Button.svelte';
	import SegmentedControl from '../../lib/ui/SegmentedControl.svelte';
	import Skeleton from '../../lib/ui/Skeleton.svelte';
	import Switch from '../../lib/ui/Switch.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { ts } from '../../lib/settings/messages';
	import { STUDY_SETTINGS, StudySettingsState, type SettingDef } from '../../lib/state/settings.svelte';
	import { sx, type SettingsViewKey } from './messages';

	const vm = new StudySettingsState();
	$effect(() => {
		void vm.load();
	});

	const GROUPS: { title: SettingsViewKey; keys: string[] }[] = [
		{ title: 'sx.day', keys: ['scheduler.timezone', 'scheduler.day_rollover_hour'] },
		{ title: 'sx.limits', keys: ['scheduler.new_per_day', 'scheduler.reviews_per_day', 'scheduler.new_per_session'] },
		{ title: 'sx.grading', keys: ['scheduler.grading_buttons', 'scheduler.leech_threshold'] },
		{ title: 'sx.queue', keys: ['scheduler.bury_siblings', 'scheduler.kanji_before_vocab', 'scheduler.mined_priority'] },
		{ title: 'sx.other', keys: ['audio.prefetch_days'] }
	];
	const defs = (keys: string[]): SettingDef[] => keys.map((k) => STUDY_SETTINGS.find((d) => d.key === k)).filter((d): d is SettingDef => !!d);

	function save(e: Event): void {
		e.preventDefault();
		void vm.save();
	}
</script>

{#if !vm.loaded}
	<Skeleton shape="row" count={5} />
{:else}
	<form class="study" onsubmit={save}>
		{#each GROUPS as g (g.title)}
			<fieldset>
				<legend>{sx(g.title)}</legend>
				{#each defs(g.keys) as def (def.key)}
					<div class="row">
						{#if def.type === 'bool'}
							<Switch label={ts(def.label)} checked={vm.draft[def.key] === true} onchange={(v) => (vm.draft[def.key] = v)} />
						{:else if def.type === 'buttons'}
							<span class="label">{ts(def.label)}</span>
							<SegmentedControl
								label={ts(def.label)}
								value={String(vm.draft[def.key]) === '5' ? '5' : '4'}
								onchange={(v) => (vm.draft[def.key] = v)}
								options={[
									{ value: '4', label: ts('settings.buttons4') },
									{ value: '5', label: ts('settings.buttons5') }
								]}
							/>
						{:else}
							<div class="field">
								<TextField
									label={ts(def.label)}
									inputmode={def.type === 'int' ? 'numeric' : undefined}
									value={String(vm.draft[def.key] ?? '')}
									oninput={(e) => (vm.draft[def.key] = (e.currentTarget as HTMLInputElement).value)}
								/>
							</div>
						{/if}
					</div>
				{/each}
			</fieldset>
		{/each}
		<div class="actions"><Button variant="primary" type="submit" loading={vm.busy}>{sx('sx.saveStudy')}</Button></div>
	</form>
{/if}

<style>
	.study {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
	}
	fieldset {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-1);
		margin: 0;
		padding: 0;
		border: 0;
	}
	legend {
		margin-bottom: var(--kb-space-2);
		font: var(--kb-type-overline);
		letter-spacing: var(--kb-type-overline-tracking);
		text-transform: uppercase;
		color: var(--kb-color-text-muted);
	}
	.row {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-2);
		padding: var(--kb-space-2) 0;
		border-bottom: 1px solid var(--kb-color-border);
	}
	fieldset .row:last-child {
		border-bottom: 0;
	}
	.label {
		font: var(--kb-type-body);
	}
	.field {
		max-width: 22rem;
	}
	.actions {
		display: flex;
		justify-content: flex-end;
	}
</style>
