package logicsim;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Serial event dispatch, including previews while editing a stopped circuit. */
public class Simulation implements Runnable {
	private static final Simulation INSTANCE = new Simulation();
	private volatile boolean running;
	private volatile long generation;
	private final Object executionLock = new Object();
	private final Queue<LSLevelEvent> wires = new ArrayDeque<LSLevelEvent>();
	private final Queue<LSLevelEvent> gates = new ArrayDeque<LSLevelEvent>();
	private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread timer = new Thread(task, "LogicSim timers");
		timer.setDaemon(true);
		return timer;
	});
	private final Thread thread;
	private boolean processing;
    // Count repeated dispatches per component, not total events: large circuits
    // and fanout must remain usable. A new external stimulus starts a new budget.
    private static final int MAX_COMPONENT_EVENTS = 4096;
    private final java.util.IdentityHashMap<LSLevelListener, Integer> dispatchCounts = new java.util.IdentityHashMap<>();
    private final ThreadLocal<Boolean> suppressEvents = new ThreadLocal<Boolean>();
    private volatile boolean oscillating;
    private volatile Runnable instabilityListener = () -> {};

    public <T> T withoutEvents(java.util.function.Supplier<T> factory) {
        Boolean previous = suppressEvents.get();
        suppressEvents.set(true);
        try { return factory.get(); }
        finally { if (previous == null) suppressEvents.remove(); else suppressEvents.set(previous); }
    }

    public boolean isOscillating() { return oscillating; }
    public void setInstabilityListener(Runnable listener) { instabilityListener = listener; }

	private long dispatchGeneration;
	public static final int WIRE = 0;
	public static final int GATE = 1;

	public static Simulation getInstance() { return INSTANCE; }

	private Simulation() {
		thread = new Thread(this, "LogicSim simulation");
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.setDaemon(true);
		thread.start();
	}

	public void start() {
		synchronized (executionLock) { oscillating = false; running = true; }
	}

	/** Queue the complete initial state before dispatching any of its signals. */
	void initializeCircuit(Runnable initialization) {
		// Use the same lock order as dispatch, stop and timer callbacks. Nested
		// module resets are safe because Java monitors are reentrant.
		synchronized (executionLock) { initialization.run(); }
	}

	/** Cancel pending propagation and timers; later editing events remain usable. */
	public void stop() {
		synchronized (executionLock) {
			running = false;
            oscillating = false;
			synchronized (this) {
				// Invalidate and clear atomically with dequeueing. Otherwise an old
				// queued event could acquire the new generation during stop().
				generation++;
                dispatchCounts.clear();
				wires.clear();
				gates.clear();
				notifyAll();
			}
		}
	}

	public boolean isRunning() { return running; }

	/** No clock tick can complete after stop() returns. */
	public void runIfRunning(Runnable task) {
		synchronized (executionLock) {
			if (running) task.run();
		}
	}

	/** A stop invalidates old timers even when the simulation is started again. */
	public ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
		final long scheduledGeneration = generation;
		return timers.schedule(() -> {
			synchronized (executionLock) {
				if (scheduledGeneration == generation) task.run();
			}
		}, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
	}

	public synchronized void putEvent(LSLevelEvent event) {
		if (event.target == null || Boolean.TRUE.equals(suppressEvents.get())) return;
        if (Thread.currentThread() != thread) dispatchCounts.clear();
		if (Thread.currentThread() == thread && dispatchGeneration != generation) return;
		if (event.target instanceof Wire || event.target instanceof WirePoint)
			wires.add(event);
		else if (event.target instanceof Pin || event.target instanceof Gate)
			gates.add(event);
		notifyAll();
	}

	/** Wait for propagation without depending on a fixed simulation speed. */
	public synchronized boolean awaitIdle(long timeoutMillis) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (processing || !wires.isEmpty() || !gates.isEmpty()) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) return false;
			TimeUnit.NANOSECONDS.timedWait(this, remaining);
		}
		return true;
	}

	@Override
	public void run() {
		int mode = WIRE;
		while (!Thread.currentThread().isInterrupted()) {
			LSLevelEvent event;
			long eventGeneration;
			synchronized (this) {
				while (wires.isEmpty() && gates.isEmpty()) {
                    dispatchCounts.clear();
					try { wait(); }
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
				Queue<LSLevelEvent> queue = mode == WIRE ? wires : gates;
				if (queue.isEmpty()) {
					mode = mode == WIRE ? GATE : WIRE;
					queue = mode == WIRE ? wires : gates;
				}
				event = queue.remove();
				eventGeneration = generation;
				processing = true;
			}
			try {
				// Never hold the queue monitor while calling a circuit component.
				synchronized (executionLock) {
					if (eventGeneration == generation) {
						dispatchGeneration = eventGeneration;
                        boolean unstable;
                        synchronized (this) {
                            Integer count = dispatchCounts.get(event.target);
                            int next = count == null ? 1 : count + 1;
                            dispatchCounts.put(event.target, next);
                            unstable = next > MAX_COMPONENT_EVENTS;
                        }
                        if (unstable) {
                            stop();
                            oscillating = true;
                            instabilityListener.run();
                        } else event.target.changedLevel(event);
					}
				}
			} catch (RuntimeException e) {
				e.printStackTrace();
			} finally {
				synchronized (this) {
					processing = false;
					notifyAll();
				}
			}
		}
	}
}
