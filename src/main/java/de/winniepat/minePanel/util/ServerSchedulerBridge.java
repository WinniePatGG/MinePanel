package de.winniepat.minePanel.util;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ServerSchedulerBridge {

    private final JavaPlugin plugin;
    private final boolean folia;
    private final Object globalRegionScheduler;
    private final Method globalRunMethod;
    private final Method globalRunDelayedMethod;
    private final Method globalRunAtFixedRateMethod;
    private final Object asyncScheduler;
    private final Method asyncRunNowMethod;

    public ServerSchedulerBridge(JavaPlugin plugin) {
        this.plugin = plugin;

        Object resolvedGlobalScheduler = null;
        Method resolvedGlobalRun = null;
        Method resolvedGlobalRunDelayed = null;
        Method resolvedGlobalRunAtFixedRate = null;

        Object resolvedAsyncScheduler = null;
        Method resolvedAsyncRunNow = null;

        try {
            resolvedGlobalScheduler = plugin.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(plugin.getServer());
            resolvedGlobalRun = resolvedGlobalScheduler.getClass().getMethod("run", Plugin.class, Consumer.class);
            resolvedGlobalRunDelayed = resolvedGlobalScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
            resolvedGlobalRunAtFixedRate = resolvedGlobalScheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);

            resolvedAsyncScheduler = plugin.getServer().getClass().getMethod("getAsyncScheduler").invoke(plugin.getServer());
            resolvedAsyncRunNow = resolvedAsyncScheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class);
        } catch (ReflectiveOperationException ignored) {
            // Paper/Spigot fallback uses BukkitScheduler APIs.
        }

        this.globalRegionScheduler = resolvedGlobalScheduler;
        this.globalRunMethod = resolvedGlobalRun;
        this.globalRunDelayedMethod = resolvedGlobalRunDelayed;
        this.globalRunAtFixedRateMethod = resolvedGlobalRunAtFixedRate;
        this.asyncScheduler = resolvedAsyncScheduler;
        this.asyncRunNowMethod = resolvedAsyncRunNow;
        this.folia = this.globalRegionScheduler != null && this.globalRunMethod != null;
    }

    public boolean isFolia() {
        return folia;
    }

    public CancellableTask runRepeatingGlobal(Runnable task, long initialDelayTicks, long periodTicks) {
        if (!folia) {
            BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
            return bukkitTask::cancel;
        }

        if (globalRunDelayedMethod == null) {
            try {
                Object scheduledTask = globalRunAtFixedRateMethod.invoke(
                        globalRegionScheduler,
                        plugin,
                        (Consumer<Object>) ignored -> task.run(),
                        initialDelayTicks,
                        periodTicks
                );
                return () -> cancelFoliaTask(scheduledTask);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("could_not_schedule_global_repeating_task", exception);
            }
        }

        long safeInitialDelay = Math.max(0L, initialDelayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Object> scheduledTaskRef = new AtomicReference<>();

        class FoliaRepeatingState {
            void scheduleNext(long delayTicks) {
                if (cancelled.get()) {
                    return;
                }

                try {
                    Object scheduledTask = globalRunDelayedMethod.invoke(
                            globalRegionScheduler,
                            plugin,
                            (Consumer<Object>) ignored -> {
                                if (cancelled.get()) {
                                    return;
                                }

                                task.run();
                                scheduleNext(safePeriod);
                            },
                            Math.max(0L, delayTicks)
                    );
                    scheduledTaskRef.set(scheduledTask);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("could_not_schedule_global_repeating_task", exception);
                }
            }
        }

        FoliaRepeatingState repeatingState = new FoliaRepeatingState();
        // Do not block startup waiting for first tick; on Folia this can deadlock when called during enable.
        repeatingState.scheduleNext(safeInitialDelay);

        return () -> {
            cancelled.set(true);
            cancelFoliaTask(scheduledTaskRef.get());
        };
    }

    public void runGlobal(Runnable task) {
        if (!folia) {
            plugin.getServer().getScheduler().runTask(plugin, task);
            return;
        }

        try {
            globalRunMethod.invoke(globalRegionScheduler, plugin, (Consumer<Object>) ignored -> task.run());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("could_not_schedule_global_task", exception);
        }
    }

    public void runAsync(Runnable task) {
        if (!folia || asyncScheduler == null || asyncRunNowMethod == null) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
            return;
        }

        try {
            asyncRunNowMethod.invoke(asyncScheduler, plugin, (Consumer<Object>) ignored -> task.run());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("could_not_schedule_async_task", exception);
        }
    }

    public <T> T callGlobal(Callable<T> task, long timeout, TimeUnit timeUnit)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<T> future = new CompletableFuture<>();
        runGlobal(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future.get(timeout, timeUnit);
    }

    private void cancelFoliaTask(Object task) {
        if (task == null) {
            return;
        }

        try {
            Method cancelMethod = task.getClass().getMethod("cancel");
            cancelMethod.invoke(task);
        } catch (ReflectiveOperationException ignored) {
            // Best-effort cancellation.
        }
    }

    @FunctionalInterface
    public interface CancellableTask {
        void cancel();
    }
}

