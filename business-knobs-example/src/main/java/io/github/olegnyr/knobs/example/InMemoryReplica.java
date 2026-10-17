package io.github.olegnyr.knobs.example;

import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterReplica;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Реплика в памяти — место таблицы в базе сервиса. Настоящая реплика хранит слепки записей
 * топика, чтобы правка из админки пережила рестарт пода; её {@code saveIfNewer} — условная запись
 * по версии, например {@code UPDATE ... WHERE version < ?}.
 */
final class InMemoryReplica implements ParameterReplica {

    private final ConcurrentMap<String, ParameterRecord> rows = new ConcurrentHashMap<>();

    @Override
    public List<ParameterRecord> findAll() {
        return List.copyOf(rows.values());
    }

    @Override
    public boolean saveIfNewer(ParameterRecord record) {
        AtomicBoolean saved = new AtomicBoolean();
        rows.compute(record.getKey(), (key, stored) -> {
            if (stored != null && stored.getVersion() >= record.getVersion()) {
                return stored;
            }
            saved.set(true);
            return record;
        });
        return saved.get();
    }

    @Override
    public Optional<ParameterRecord> find(String key) {
        return Optional.ofNullable(rows.get(key));
    }
}
