import { describe, expect, it } from 'vitest';
import { coreVersion } from '@kotobaya/core';

describe('@kotobaya/core', () => {
	it('exports a @JsExport function', () => {
		expect(coreVersion()).toBe('kotobaya-core-0');
	});
});
