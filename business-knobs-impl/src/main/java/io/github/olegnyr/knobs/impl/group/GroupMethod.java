package io.github.olegnyr.knobs.impl.group;

import io.github.olegnyr.knobs.KnobKey;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Разобранный метод группы: ключ, тип для читателя и признак {@code Optional}.
 * Разбор — при создании прокси, чтобы ошибка объявления всплыла на старте, а не при первом вызове.
 *
 * @param key      полный ключ параметра
 * @param type     тип для читателя: примитив упакован, {@code Optional} снят
 * @param optional метод возвращает {@code Optional}
 */
record GroupMethod(String key, Type type, boolean optional) {

    private static final Map<Class<?>, Class<?>> BOXED = Map.of(
            boolean.class, Boolean.class,
            int.class, Integer.class,
            long.class, Long.class,
            double.class, Double.class,
            float.class, Float.class,
            short.class, Short.class,
            byte.class, Byte.class,
            char.class, Character.class);

    static GroupMethod of(String prefix, Method method, Class<?> group) {
        if (method.getParameterCount() > 0) {
            throw new IllegalArgumentException("Метод группы " + method.getDeclaringClass().getSimpleName() + "#"
                    + method.getName() + " с аргументами: геттер параметра аргументов не принимает");
        }
        if (method.getReturnType() == void.class) {
            throw new IllegalArgumentException("Метод группы " + method.getDeclaringClass().getSimpleName() + "#"
                    + method.getName() + " без результата");
        }
        KnobKey explicit = method.getAnnotation(KnobKey.class);
        String tail = explicit != null ? explicit.value() : kebab(method.getName());
        String key = prefix.isEmpty() ? tail : prefix + "." + tail;
        Type returned = GroupTypes.resolve(method.getGenericReturnType(), group);
        if (method.getReturnType() == Optional.class) {
            Type inner = returned instanceof ParameterizedType parameterized
                    ? parameterized.getActualTypeArguments()[0] : Object.class;
            return new GroupMethod(key, inner, true);
        }
        Class<?> boxed = BOXED.get(method.getReturnType());
        return new GroupMethod(key, boxed != null && returned == method.getReturnType() ? boxed : returned, false);
    }

    /**
     * {@code hopLimit} → {@code hop-limit}, {@code maxWaitSec} → {@code max-wait-sec}.
     */
    static String kebab(String name) {
        StringBuilder key = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char symbol = name.charAt(i);
            if (Character.isUpperCase(symbol)) {
                key.append('-').append(Character.toLowerCase(symbol));
            } else {
                key.append(symbol);
            }
        }
        return key.toString().toLowerCase(Locale.ROOT);
    }
}
