package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterReplica;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Читает реплику параметров из базы и кладёт её поверх файла.
 *
 * <p>Зовётся до чтения топика: топик дочитывается уже поверх реплики. В Spring — после создания
 * всех бинов и до старта жизненного цикла, то есть до старта слушателей Kafka; без Spring — при
 * сборке {@link Knobs}. Ключи, где реплика разошлась с файлом, уходят событием смены, и как
 * применить смену, решает потребитель.
 *
 * <p>База недоступна — под остаётся на файле, подъём не падает: снимок отмечен «реплика
 * не прочитана», в логе — класс причины, без текста (в нём могут быть значения).
 */
@Slf4j
public final class ParameterReplicaLoader {

    private final ParameterStore store;

    private final Supplier<ParameterReplica> replica;

    private final BooleanSupplier replicaEnabled;

    private final ParameterEvents events;

    /**
     * Загрузчик реплики.
     *
     * @param store          снимок пода; без файла стенда — пустой
     * @param replica        реплика сервиса; {@code null} из поставщика — под на файле
     * @param replicaEnabled рубильник {@link ParameterRecordApplierImpl#REPLICA_ENABLED}
     * @param events         рассылка смены
     */
    public ParameterReplicaLoader(ParameterStore store, Supplier<ParameterReplica> replica,
                                  BooleanSupplier replicaEnabled, ParameterEvents events) {
        this.store = store;
        this.replica = replica;
        this.replicaEnabled = replicaEnabled;
        this.events = events;
    }

    /**
     * Читает реплику и кладёт её поверх файла.
     */
    public void load() {
        if (!replicaEnabled.getAsBoolean()) {
            log.warn("Рубильник {} = false: реплика параметров не читается, работаю на файле",
                    ParameterRecordApplierImpl.REPLICA_ENABLED);
            return;
        }
        ParameterReplica source = replica.get();
        if (source == null) {
            log.info("Реплики параметров в сервисе нет: работаю на файле");
            return;
        }
        List<ParameterRecord> records;
        try {
            records = source.findAll();
        } catch (RuntimeException e) {
            log.warn("Реплика параметров не прочитана, работаю на файле: причина {}", e.getClass().getName());
            return;
        }
        Set<String> changed = store.replaceWithReplica(store.parameters().withReplica(records));
        log.info("Реплика параметров прочитана: строк {}, сменилось ключей {}", records.size(), changed.size());
        events.publish(changed);
    }
}
