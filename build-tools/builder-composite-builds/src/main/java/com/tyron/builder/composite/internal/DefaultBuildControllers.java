package com.tyron.builder.composite.internal;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.execution.plan.PlanExecutor;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.api.internal.artifacts.DefaultBuildIdentifier;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.concurrent.ManagedExecutor;
import com.tyron.builder.internal.work.WorkerLeaseService;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.ExecutionResult;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class DefaultBuildControllers implements BuildControllers {
    // Always iterate over the controllers in a fixed order
    private final Map<BuildIdentifier, BuildController> controllers = new TreeMap<>(idComparator());
    private final ManagedExecutor executorService;
    private final WorkerLeaseService workerLeaseService;
    private final PlanExecutor planExecutor;
    private final int monitoringPollTime;
    private final TimeUnit monitoringPollTimeUnit;

    DefaultBuildControllers(ManagedExecutor executorService, WorkerLeaseService workerLeaseService, PlanExecutor planExecutor, int monitoringPollTime, TimeUnit monitoringPollTimeUnit) {
        this.executorService = executorService;
        this.workerLeaseService = workerLeaseService;
        this.planExecutor = planExecutor;
        this.monitoringPollTime = monitoringPollTime;
        this.monitoringPollTimeUnit = monitoringPollTimeUnit;
    }

    @Override
    public BuildController getBuildController(BuildState build) {
        BuildController buildController = controllers.get(build.getBuildIdentifier());
        if (buildController != null) {
            return buildController;
        }

        BuildController newBuildController = new DefaultBuildController(build, workerLeaseService);
        controllers.put(build.getBuildIdentifier(), newBuildController);
        return newBuildController;
    }

    @Override
    public void populateWorkGraphs() {
        boolean tasksDiscovered = true;
        while (tasksDiscovered) {
            tasksDiscovered = false;
            for (BuildController buildController : ImmutableList.copyOf(controllers.values())) {
                if (buildController.scheduleQueuedTasks()) {
                    tasksDiscovered = true;
                }
            }
        }
        for (BuildController buildController : controllers.values()) {
            buildController.finalizeWorkGraph();
        }
    }

    @Override
    public ExecutionResult<Void> execute() {
        CountDownLatch complete = new CountDownLatch(controllers.size());
        Map<BuildController, ExecutionResult<Void>> results = new ConcurrentHashMap<>();

        // Start work in each build
        for (BuildController buildController : controllers.values()) {
            buildController.startExecution(executorService, result -> {
                results.put(buildController, result);
                complete.countDown();
            });
        }

        awaitCompletion(complete);

        // Collect the failures in deterministic order
        ExecutionResult<Void> result = ExecutionResult.succeeded();
        for (BuildController buildController : controllers.values()) {
            result = result.withFailures(results.get(buildController));
        }
        return result;
    }

    private void awaitCompletion(CountDownLatch complete) {
        while (true) {
            // Wake for the work in all builds to complete. Periodically wake up and check the executor health

            AtomicBoolean done = new AtomicBoolean();
            // Ensure that this thread does not hold locks while waiting and so prevent this work from completing
            workerLeaseService.blocking(() -> {
                try {
                    done.set(complete.await(monitoringPollTime, monitoringPollTimeUnit));
                } catch (InterruptedException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            });
            if (done.get()) {
                return;
            }

            planExecutor.assertHealthy();
        }
    }

    @Override
    public void close() {
        CompositeStoppable.stoppable(controllers.values()).stop();
    }

    private Comparator<BuildIdentifier> idComparator() {
        return (id1, id2) -> {
            // Root is always last
            if (id1.equals(DefaultBuildIdentifier.ROOT)) {
                if (id2.equals(DefaultBuildIdentifier.ROOT)) {
                    return 0;
                } else {
                    return 1;
                }
            }
            if (id2.equals(DefaultBuildIdentifier.ROOT)) {
                return -1;
            }
            return id1.getName().compareTo(id2.getName());
        };
    }
}
