// How the shell answers each API operation (contracts/api/shell/ops.json, SHELL.md 3.2). The UI gates on this
// (SHELL.md 9.4), never on "is Android".
import ops from '../../../../../contracts/api/shell/ops.json' with { type: 'json' };

export type OpClass = 'local' | 'local_or_proxy' | 'proxy' | 'hidden';

const CLASSES: Readonly<Record<string, OpClass>> = ops as Record<string, OpClass>;

/** The class of an openapi.yaml operationId; undefined for an id ops.json does not list. */
export function opClass(operationId: string): OpClass | undefined {
	return Object.hasOwn(CLASSES, operationId) ? CLASSES[operationId] : undefined;
}
