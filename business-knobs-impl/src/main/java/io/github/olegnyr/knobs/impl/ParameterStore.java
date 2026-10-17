package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.impl.evaluation.ContextDimensions;
import io.github.olegnyr.knobs.impl.snapshot.ParameterSet;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Действующий снимок параметров пода: единственное место, где он живёт,
 * со Spring и без него.
 *
 * <p>Состояние — один неизменяемый объект за {@code volatile}: читатель видит либо прежний
 * снимок, либо новый, без замка. Замена идёт под замком и возвращает ключи, чьё действующее
 * определение сменилось, — значение либо правила, в том числе правила по обращению, которые на
 * значение пода не влияют: их видит чтение с контекстом, и его потребитель тоже должен узнать о
 * смене. Spring-адаптер читает отсюда свойства пода, но снимком не владеет.
 */
public final class ParameterStore {

    private volatile State state;

    /**
     * Замок замены снимка: читатели идут по volatile-состоянию без него.
     */
    private final ReentrantLock replaceLock = new ReentrantLock();

    /**
     * Хранилка над снимком из файла стенда.
     *
     * @param parameters снимок параметров пода
     * @param podPath    путь пода: {@code service} и {@code dc}
     */
    public ParameterStore(ParameterSet parameters, ContextPath podPath) {
        this.state = new State(parameters, podPath, false);
    }

    /**
     * Пустая хранилка: у сервиса нет файла стенда. Параметров нет, записи топика — чужие ключи,
     * правка отбивается ключом вне каталога; путь пода всё равно известен — его отдают метрики.
     *
     * @param podPath    путь пода: {@code service} и {@code dc}
     * @param dimensions измерения пути в порядке точности
     * @return хранилка над пустым снимком
     */
    public static ParameterStore empty(ContextPath podPath, ContextDimensions dimensions) {
        return new ParameterStore(ParameterSet.empty(dimensions), podPath);
    }

    /**
     * Действующий снимок.
     *
     * @return снимок параметров
     */
    public ParameterSet parameters() {
        return state.parameters();
    }

    /**
     * Путь пода: сервис и ЦОД.
     *
     * @return путь пода
     */
    public ContextPath podPath() {
        return state.podPath();
    }

    /**
     * Прочитана ли реплика: {@code false} — под работает на файле.
     *
     * @return признак слоя реплики
     */
    public boolean isReplicaLoaded() {
        return state.replicaLoaded();
    }

    /**
     * Заменяет снимок: запись топика, аварийная правка. Признак «реплика прочитана» не меняется.
     *
     * @param parameters новый снимок
     * @return ключи, чьё действующее определение сменилось
     */
    public Set<String> replace(ParameterSet parameters) {
        replaceLock.lock();
        try {
            return replace(parameters, state.replicaLoaded());
        } finally {
            replaceLock.unlock();
        }
    }

    /**
     * Заменяет снимок прочитанной репликой и отмечает «реплика прочитана».
     *
     * @param parameters снимок со слоем реплики
     * @return ключи, чьё действующее определение сменилось
     */
    public Set<String> replaceWithReplica(ParameterSet parameters) {
        replaceLock.lock();
        try {
            return replace(parameters, true);
        } finally {
            replaceLock.unlock();
        }
    }

    private Set<String> replace(ParameterSet parameters, boolean replicaLoaded) {
        Set<String> changed = changedKeys(state.parameters(), parameters);
        //noinspection NonAtomicOperationOnVolatileField
        state = new State(parameters, state.podPath(), replicaLoaded);
        return changed;
    }

    /**
     * Путь пода в снимке не меняется, поэтому сменившееся значение пода невозможно без
     * сменившегося определения: сравнения определений достаточно.
     */
    private static Set<String> changedKeys(ParameterSet before, ParameterSet after) {
        Set<String> keys = new LinkedHashSet<>();
        before.parameters().forEach(parameter -> keys.add(parameter.getKey()));
        after.parameters().forEach(parameter -> keys.add(parameter.getKey()));
        Set<String> changed = new LinkedHashSet<>();
        for (String key : keys) {
            if (!before.find(key).equals(after.find(key))) {
                changed.add(key);
            }
        }
        return changed;
    }

    private record State(ParameterSet parameters, ContextPath podPath, boolean replicaLoaded) {
    }
}
