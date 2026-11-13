<script lang="ts">
	import { icons, type IconName } from './icons';

	interface Props {
		name: IconName;
		/** px; defaults to the --kb-size-icon token (20). */
		size?: number;
		/** Accessible name; omit for decorative icons (the usual case: icons sit next to a text label). */
		label?: string;
	}
	let { name, size = 20, label }: Props = $props();

	const parts = $derived(
		icons[name].map((d) => {
			const [kind, ...rest] = d.split(' ');
			if (kind === 'c' || kind === 'f') {
				const [cx, cy, r] = rest.map(Number);
				return { circle: true as const, cx, cy, r, filled: kind === 'f' };
			}
			return { circle: false as const, d };
		})
	);
</script>

<svg
	class="icon"
	width={size}
	height={size}
	viewBox="0 0 24 24"
	fill="none"
	stroke="currentColor"
	stroke-width="1.75"
	stroke-linecap="round"
	stroke-linejoin="round"
	role={label ? 'img' : undefined}
	aria-label={label}
	aria-hidden={label ? undefined : 'true'}
	focusable="false"
>
	{#each parts as p, i (i)}
		{#if p.circle}
			<circle cx={p.cx} cy={p.cy} r={p.r} fill={p.filled ? 'currentColor' : 'none'} stroke={p.filled ? 'none' : 'currentColor'} />
		{:else}
			<path d={p.d} />
		{/if}
	{/each}
</svg>

<style>
	.icon {
		flex: none;
		display: block;
	}
</style>
