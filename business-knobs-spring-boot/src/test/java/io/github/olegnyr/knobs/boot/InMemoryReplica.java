package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterReplica;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Реплика в памяти с условной записью по версии, как у настоящей, и счётчиком обращений. */
final class InMemoryReplica implements ParameterReplica {

    private final Map<String, ParameterRecord> rows = new LinkedHashMap<>();

    final AtomicInteger calls = new AtomicInteger();

    boolean failing;

    @Override
    public synchronized List<ParameterRecord> findAll() {
        touch();
        return new ArrayList<>(rows.values());
    }

    @Override
    public synchronized boolean saveIfNewer(ParameterRecord record) {
        touch();
        ParameterRecord stored = rows.get(record.getKey());
        if (stored != null && stored.getVersion() >= record.getVersion()) {
            return false;
        }
        rows.put(record.getKey(), record);
        return true;
    }

    @Override
    public synchronized Optional<ParameterRecord> find(String key) {
        touch();
        return Optional.ofNullable(rows.get(key));
    }

    synchronized void put(ParameterRecord record) {
        rows.put(record.getKey(), record);
    }

    private void touch() {
        calls.incrementAndGet();
        if (failing) {
            throw new IllegalStateException("база недоступна");
        }
    }
}
