// Forward-deploy fallback: preserve the SQLite class/migration while bypassing
// the private runtime. The existing full-content/TOC API contract remains intact.
export { ReaderSourceRuntime } from './runtime-entry';
export { default } from './index';
