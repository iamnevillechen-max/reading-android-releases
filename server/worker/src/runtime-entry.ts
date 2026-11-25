import { DurableObject } from 'cloudflare:workers';
import { createApp } from './index';
import { createGateway, createRuntimeReceiver, type RuntimeEnv } from './gateway-routing';

/** SQLite-backed Free DO; the existing D1 application owns all persistence. */
export class ReaderSourceRuntime extends DurableObject<RuntimeEnv> {
  readonly #receiver: ReturnType<typeof createRuntimeReceiver>;
  constructor(context: DurableObjectState, env: RuntimeEnv) {
    super(context, env);
    // The app's scheduled implementation awaits its work. DO waitUntil remains
    // available, without introducing storage, alarms or a background timer.
    this.#receiver = createRuntimeReceiver(createApp(), env, context as unknown as ExecutionContext);
  }
  fetch(request: Request): Promise<Response> { return this.#receiver.fetch(request); }
}

export default createGateway();
