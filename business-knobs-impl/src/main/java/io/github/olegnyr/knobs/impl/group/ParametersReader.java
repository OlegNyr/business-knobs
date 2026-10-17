package io.github.olegnyr.knobs.impl.group;

import io.github.olegnyr.knobs.ParameterTypeMismatchException;
import io.github.olegnyr.knobs.Parameters;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Читатель группы без Spring: значение из {@link Parameters} уже в типе
 * Java ({@code ParameterType#read}), здесь — приведение к типу метода по тем же правилам, что у
 * {@code Binder} Spring: сужение целого ({@code INTEGER} хранится как {@code Long}), строка —
 * запись значения, список — по элементам в {@code List} либо {@code Set}. Одна и та же группа
 * отвечает одинаково со Spring и без него.
 */
public final class ParametersReader implements ParameterReader {

    private final Parameters parameters;

    /**
     * Читатель над чтением параметров.
     *
     * @param parameters чтение с контекстом обращения
     */
    public ParametersReader(Parameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public Optional<Object> read(String key, Type type) {
        return parameters.value(key).map(value -> coerce(key, value, type));
    }

    private static Object coerce(String key, Object value, Type type) {
        Class<?> raw = rawClass(type);
        if (Collection.class.isAssignableFrom(raw)) {
            if (!(value instanceof List<?> list)) {
                throw mismatch(key, raw, value.getClass().getSimpleName());
            }
            Type elementType = elementType(type);
            List<Object> elements = new ArrayList<>(list.size());
            for (Object element : list) {
                elements.add(scalar(key, element, elementType));
            }
            if (raw.isAssignableFrom(List.class)) {
                return List.copyOf(elements);
            }
            if (raw.isAssignableFrom(Set.class)) {
                return Collections.unmodifiableSet(new LinkedHashSet<>(elements));
            }
            throw mismatch(key, raw, "List");
        }
        if (value instanceof List<?>) {
            throw mismatch(key, raw, "List");
        }
        return scalar(key, value, raw);
    }

    /**
     * Скаляр в типе метода — по тем же правилам, что у {@code Binder} Spring над записью файла:
     * целое сужается до {@code int} либо расширяется до {@code double}, строка — запись значения.
     */
    private static Object scalar(String key, Object value, Type type) {
        Class<?> raw = rawClass(type);
        if (raw.isInstance(value)) {
            return value;
        }
        if (raw == String.class) {
            return String.valueOf(value);
        }
        if (value instanceof Long number) {
            return narrow(key, number, raw);
        }
        throw mismatch(key, raw, value.getClass().getSimpleName());
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            return (Class<?>) parameterized.getRawType();
        }
        return type instanceof Class<?> raw ? raw : Object.class;
    }

    private static Type elementType(Type type) {
        return type instanceof ParameterizedType parameterized
                ? parameterized.getActualTypeArguments()[0] : Object.class;
    }

    private static Object narrow(String key, Long number, Class<?> raw) {
        if (raw == Integer.class) {
            if (number != number.intValue()) {
                throw new ParameterTypeMismatchException(key, "Integer", "Long вне диапазона int");
            }
            return number.intValue();
        }
        if (raw == Double.class) {
            return number.doubleValue();
        }
        throw mismatch(key, raw, "Long");
    }

    private static ParameterTypeMismatchException mismatch(String key, Class<?> expected, String actual) {
        return new ParameterTypeMismatchException(key, expected.getSimpleName(), actual);
    }
}
