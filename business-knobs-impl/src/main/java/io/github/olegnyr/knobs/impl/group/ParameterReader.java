package io.github.olegnyr.knobs.impl.group;

import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Откуда прокси группы берёт значение: без Spring — {@code Parameters}
 * с приведением типов библиотеки ({@link ParametersReader}), со Spring — {@code Binder} над слоем
 * параметров. Прокси один, читатели разные.
 */
@FunctionalInterface
public interface ParameterReader {

    /**
     * Значение параметра в типе метода группы.
     *
     * @param key  полный ключ параметра
     * @param type тип, который ждёт метод: примитив уже упакован, {@code Optional} снят, у списка
     *             сохранён аргумент типа
     * @return значение нужного типа; пусто — значения нет ни в одном источнике
     * @throws io.github.olegnyr.knobs.ParameterTypeMismatchException значение к типу не приводится
     */
    Optional<Object> read(String key, Type type);
}
