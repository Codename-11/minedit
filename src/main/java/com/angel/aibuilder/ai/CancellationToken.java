package com.angel.aibuilder.ai;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Thread> workerThread = new AtomicReference<>();
    private final List<Closeable> closeables = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        for (Closeable closeable : closeables) {
            closeQuietly(closeable);
        }
        Thread thread = workerThread.get();
        if (thread != null) {
            thread.interrupt();
        }
    }

    public void attachCurrentThread() {
        workerThread.set(Thread.currentThread());
        if (isCancelled()) {
            Thread.currentThread().interrupt();
        }
    }

    public void detachCurrentThread() {
        workerThread.compareAndSet(Thread.currentThread(), null);
    }

    public void register(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        closeables.add(closeable);
        if (isCancelled() && closeables.remove(closeable)) {
            closeQuietly(closeable);
        }
    }

    public void unregister(Closeable closeable) {
        closeables.remove(closeable);
    }

    public void throwIfCancelled() throws InterruptedException {
        if (isCancelled()) {
            throw new InterruptedException("Minedit job was stopped.");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Minedit job was interrupted.");
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing during cancellation is best-effort.
        }
    }
}
