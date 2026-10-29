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
		synchronized (executionLock) { running = true; }
	}

	/** Cancel pending propagation and timers; later editing events remain usable. */
	public void stop() {
		synchronized (executionLock) {
			running = false;
			synchronized (this) {
				// Invalidate and clear atomically with dequeueing. Otherwise an old
				// queued event could acquire the new generation during stop().
				generation++;
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
		if (event.target == null) return;
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
						event.target.changedLevel(event);
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
