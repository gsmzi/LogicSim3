package logicsim;

import java.util.concurrent.ScheduledFuture;

/** One cancellable output transition, shared by delay components. */
public final class DelayedSignal {
	private ScheduledFuture<?> pending;
	private long version;

	public synchronized void cancel() {
		version++;
		if (pending != null) {
			pending.cancel(false);
			pending = null;
		}
	}

	public synchronized void schedule(long delayMillis, Runnable action) {
		cancel();
		final long scheduledVersion = version;
		pending = Simulation.getInstance().schedule(() -> {
			synchronized (DelayedSignal.this) {
				if (version == scheduledVersion) {
					pending = null;
					action.run();
				}
			}
		}, delayMillis);
	}
}
