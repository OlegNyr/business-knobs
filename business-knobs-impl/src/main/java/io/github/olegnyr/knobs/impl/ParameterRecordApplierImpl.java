package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRecordApplier;
import io.github.olegnyr.knobs.ParameterReplica;
import io.github.olegnyr.knobs.ParameterTopicRecord;
import io.github.olegnyr.knobs.impl.format.ParameterRecordReader;
import io.github.olegnyr.knobs.impl.model.ParameterPatch;
import io.github.olegnyr.knobs.impl.snapshot.ParameterRejection;
import io.github.olegnyr.knobs.impl.snapshot.ParameterSet;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Применяет запись топика {@code knobs.parameters.v1}.
 *
 * <p>Ключ вне каталога сервиса — пропуск (это параметр другого сервиса). Версия не больше
 * версии в памяти — игнор, в базу под не ходит: так переопубликация всего набора раз в пять
 * минут безвредна. Иначе — условная запись строки реплики и чтение строки обратно: если другой
 * под уже записал версию новее, в память идёт она. Отказ базы — запись применяется в память
 * напрямую, реплику дозапишет ближайшая переопубликация.
 *
 * <p>Пачка записей сворачивается: по ключу применяется только старшая версия, событие на пачку
 * одно — так чтение топика с начала на подъёме не гоняет потребителей по каждой
 * исторической версии. Запись удалённая, не читаемая по типу каталога либо с {@code ${...}}
 * сохраняется в реплику как есть, но действует значение файла; причина — в исходе.
 *
 * <p>Ключ, чья запись в реплику не удалась, запоминается: переопубликация той же версии его
 * дописывает, иначе версия в памяти отсекла бы её как «не новее».
 *
 * <p>Записи разных партиций приходят из разных потоков; применение сериализуется, потому что
 * снимок заменяется целиком — чтение и замена должны идти без вклинившейся чужой замены.
 *
 * <p>Без Spring: снимок — {@link ParameterStore}, реплика и рубильник — поставщики, которые
 * передаёт сборщик {@link Knobs} либо Spring-адаптер.
 */
@Slf4j
public class ParameterRecordApplierImpl implements ParameterRecordApplier {

    /**
     * Рубильник: {@code false} — реплика не читается, записи топика не применяются.
     */
    public static final String REPLICA_ENABLED = "knobs.replica-enabled";

    private static final Logger AUDIT = LoggerFactory.getLogger(ParameterPatcher.AUDIT_LOGGER);

    private final ParameterStore store;

    private final Supplier<ParameterReplica> replica;

    private final BooleanSupplier replicaEnabled;

    private final ParameterEvents events;

    private final ParameterObserver observer;

    private final Set<String> unsaved = new HashSet<>();

    /**
     * Замок применения: записи топика и аварийные правки меняют один снимок по очереди.
     */
    private final ReentrantLock applyLock = new ReentrantLock();

    /**
     * Применитель записей.
     *
     * @param store          снимок пода; без файла стенда — пустой, и любая запись — чужой ключ
     * @param replica        реплика сервиса; {@code null} из поставщика — реплики нет
     * @param replicaEnabled рубильник {@link #REPLICA_ENABLED}
     * @param events         рассылка смены
     * @param observer       наблюдатель исходов
     */
    public ParameterRecordApplierImpl(ParameterStore store, Supplier<ParameterReplica> replica,
            BooleanSupplier replicaEnabled, ParameterEvents events, ParameterObserver observer) {
        this.store = store;
        this.replica = replica;
        this.replicaEnabled = replicaEnabled;
        this.events = events;
        this.observer = observer;
    }

    @Override
    public Outcome apply(String key, JsonNode body) {
        return applyAll(List.of(new ParameterTopicRecord(key, body))).getFirst();
    }

    @Override
    public List<Outcome> applyAll(List<ParameterTopicRecord> records) {
        List<Outcome> outcomes;
        Set<String> changed = new LinkedHashSet<>();
        applyLock.lock();
        try {
            outcomes = applyBatch(records, changed);
        } finally {
            applyLock.unlock();
        }
        // Потребители события работают вне замка: медленный не задерживает ни другие партиции, ни
        // аварийную правку.
        events.publish(changed);
        outcomes.forEach(observer::recordOutcome);
        return outcomes;
    }

    private List<Outcome> applyBatch(List<ParameterTopicRecord> records, Set<String> changed) {
        Outcome[] outcomes = new Outcome[records.size()];
        if (!replicaEnabled.getAsBoolean()) {
            Arrays.fill(outcomes, Outcome.SWITCHED_OFF);
            return List.of(outcomes);
        }
        ParameterSet parameters = store.parameters();
        Map<String, Integer> newest = new LinkedHashMap<>();
        Map<Integer, ParameterRecord> parsed = new LinkedHashMap<>();
        for (int i = 0; i < records.size(); i++) {
            ParameterTopicRecord topicRecord = records.get(i);
            if (topicRecord.getKey() == null) {
                outcomes[i] = Outcome.REJECTED_UNREADABLE;
                continue;
            }
            if (!parameters.inCatalog(topicRecord.getKey())) {
                outcomes[i] = Outcome.FOREIGN;
                continue;
            }
            Optional<ParameterRecord> record = parse(topicRecord);
            if (record.isEmpty()) {
                outcomes[i] = Outcome.REJECTED_UNREADABLE;
                continue;
            }
            parsed.put(i, record.get());
            Integer previous = newest.get(topicRecord.getKey());
            if (previous == null || parsed.get(previous).getVersion() < record.get().getVersion()) {
                if (previous != null) {
                    outcomes[previous] = Outcome.IGNORED;
                }
                newest.put(topicRecord.getKey(), i);
            } else {
                outcomes[i] = Outcome.IGNORED;
            }
        }
        ParameterSet next = parameters;
        boolean replaced = false;
        for (int index : newest.values()) {
            ParameterRecord record = parsed.get(index);
            long memory = next.version(record.getKey());
            if (record.getVersion() < memory || record.getVersion() == memory && !unsaved.contains(record.getKey())) {
                outcomes[index] = Outcome.IGNORED;
            } else if (record.getVersion() == memory) {
                outcomes[index] = save(record) ? Outcome.REPAIRED : Outcome.IGNORED;
            } else {
                ParameterRecord stored = stored(record);
                next = next.withRecord(stored);
                replaced = true;
                outcomes[index] = outcome(next.rejection(stored));
            }
        }
        if (replaced) {
            auditRemovedPatches(parameters, next);
            changed.addAll(store.replace(next));
        }
        return new ArrayList<>(List.of(outcomes));
    }

    /**
     * Ставит аварийные правки: все разом, одной заменой снимка. Сериализовано с
     * применением записей топика — оба меняют один снимок.
     *
     * @param patches проверенные правки ключей каталога
     */
    public void applyPatches(List<ParameterPatch> patches) {
        Set<String> changed = new LinkedHashSet<>();
        applyLock.lock();
        try {
            applyPatchesLocked(patches, changed);
        } finally {
            applyLock.unlock();
        }
        events.publish(changed);
    }

    /**
     * Снятие аварийной правки записью из админки — тоже событие аудита.
     */
    private static void auditRemovedPatches(ParameterSet before, ParameterSet after) {
        Set<String> remaining = new LinkedHashSet<>();
        after.patches().forEach(patch -> remaining.add(patch.getKey()));
        before.patches().stream()
                .filter(patch -> !remaining.contains(patch.getKey()))
                .forEach(patch -> AUDIT.info("Аварийная правка параметров снята записью из админки: key={}, version={}",
                        patch.getKey(), after.version(patch.getKey())));
    }

    private void applyPatchesLocked(List<ParameterPatch> patches, Set<String> changed) {
        ParameterSet next = store.parameters();
        for (ParameterPatch patch : patches) {
            next = next.withPatch(patch);
        }
        changed.addAll(store.replace(next));
    }

    private static Optional<ParameterRecord> parse(ParameterTopicRecord topicRecord) {
        try {
            return Optional.of(ParameterRecordReader.read(topicRecord.getBody()).toBuilder()
                    .key(topicRecord.getKey())
                    .build());
        } catch (RuntimeException e) {
            log.warn("Запись параметра не разбирается: key={}, причина {}", topicRecord.getKey(),
                    e.getClass().getName());
            return Optional.empty();
        }
    }

    private static Outcome outcome(Optional<ParameterRejection> rejection) {
        return rejection.map(reason -> switch (reason) {
            case DELETED -> Outcome.DELETED;
            case TYPE -> Outcome.REJECTED_TYPE;
            case PLACEHOLDER -> Outcome.REJECTED_PLACEHOLDER;
        }).orElse(Outcome.APPLIED);
    }

    private ParameterRecord stored(ParameterRecord record) {
        ParameterReplica source = replica.get();
        if (source == null || !save(record)) {
            return record;
        }
        try {
            return source.find(record.getKey()).orElse(record);
        } catch (RuntimeException e) {
            // Запись уже сохранена; не перечиталась — снимок берёт пришедшую, но сбой реплики виден.
            log.warn("Реплика параметров не перечитала запись key={}, беру пришедшую: причина {}",
                    record.getKey(), e.getClass().getName());
            return record;
        }
    }

    private boolean save(ParameterRecord record) {
        ParameterReplica source = replica.get();
        if (source == null) {
            return false;
        }
        try {
            source.saveIfNewer(record);
            unsaved.remove(record.getKey());
            return true;
        } catch (RuntimeException e) {
            unsaved.add(record.getKey());
            log.warn("Реплика параметров не записана, применяю в память: key={}, причина {}", record.getKey(),
                    e.getClass().getName());
            return false;
        }
    }
}
