package io.github.olegnyr.knobs;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Путь обращения переезжает между потоками через context-propagation")
class ParameterContextAccessorTest {

    private static final ContextPath VIP = ContextPath.builder().put("segment", "VIP").build();

    private static final ContextPath MASS = ContextPath.builder().put("segment", "MASS").build();

    private final ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopWorker() throws InterruptedException {
        worker.shutdownNow();
        worker.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Accessor библиотеки регистрируется сам — через ServiceLoader, без кода сервиса")
    void registry_shouldKnowParameterContext_withoutServiceCode() {
        assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
                .extracting(accessor -> accessor.key())
                .contains(ParameterContextAccessor.KEY);
    }

    @Test
    @DisplayName("Снимок, снятый на потоке приёма, открывает путь обращения на рабочем потоке и снимает по выходу")
    void snapshot_shouldCarryPathToAnotherThread_andClearItOnClose() throws Exception {
        ContextSnapshot snapshot;
        try (ParameterContext.Scope ignored = ParameterContext.open(VIP)) {
            snapshot = snapshots.captureAll();
        }

        Optional<ContextPath>[] seen = runOnWorker(() -> {
            Optional<ContextPath> inside;
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                inside = ParameterContext.current();
            }
            return new Optional[] {inside, ParameterContext.current()};
        });

        assertThat(seen).containsExactly(Optional.of(VIP), Optional.empty());
    }

    @Test
    @DisplayName("Вложенность: по выходу из снимка на потоке остаётся его прежний путь, а не пустота")
    void snapshot_shouldRestorePreviousPath_whenWorkerAlreadyHadOne() throws Exception {
        ContextSnapshot snapshot;
        try (ParameterContext.Scope ignored = ParameterContext.open(VIP)) {
            snapshot = snapshots.captureAll();
        }

        Optional<ContextPath>[] seen = runOnWorker(() -> {
            try (ParameterContext.Scope outer = ParameterContext.open(MASS)) {
                Optional<ContextPath> inside;
                try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                    inside = ParameterContext.current();
                }
                return new Optional[] {inside, ParameterContext.current()};
            }
        });

        assertThat(seen).containsExactly(Optional.of(VIP), Optional.of(MASS));
    }

    @SuppressWarnings("unchecked")
    private Optional<ContextPath>[] runOnWorker(java.util.concurrent.Callable<Optional<?>[]> task)
            throws Exception {
        return (Optional<ContextPath>[]) CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, worker).get(5, TimeUnit.SECONDS);
    }
}
