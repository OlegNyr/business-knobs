package io.github.olegnyr.knobs.impl.group;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Типы методов группы, унаследованных от обобщённого предка: {@code interface Holder<T> { T value(); }}
 * в группе {@code IntHolder extends Holder<Integer>} читается как {@code Integer}, а не как
 * переменная типа — иначе читатель отдал бы значение не того типа.
 */
final class GroupTypes {

    private GroupTypes() {
    }

    /**
     * Тип с переменными типа, подставленными по иерархии группы.
     *
     * @param type  объявленный тип метода
     * @param group тип группы
     * @return тип без переменных типа
     * @throws IllegalArgumentException переменную типа не вывести из иерархии группы
     */
    static Type resolve(Type type, Class<?> group) {
        if (!hasVariables(type)) {
            return type;
        }
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        collect(group, bindings);
        return substitute(type, bindings, group);
    }

    private static boolean hasVariables(Type type) {
        if (type instanceof TypeVariable<?>) {
            return true;
        }
        return type instanceof ParameterizedType parameterized
                && Arrays.stream(parameterized.getActualTypeArguments()).anyMatch(GroupTypes::hasVariables);
    }

    private static void collect(Type type, Map<TypeVariable<?>, Type> bindings) {
        Class<?> raw;
        if (type instanceof ParameterizedType parameterized) {
            raw = (Class<?>) parameterized.getRawType();
            TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < variables.length; i++) {
                bindings.put(variables[i], arguments[i] instanceof TypeVariable<?> variable
                        ? bindings.getOrDefault(variable, variable) : arguments[i]);
            }
        } else if (type instanceof Class<?> plain) {
            raw = plain;
        } else {
            return;
        }
        for (Type parent : raw.getGenericInterfaces()) {
            collect(parent, bindings);
        }
        if (raw.getGenericSuperclass() != null) {
            collect(raw.getGenericSuperclass(), bindings);
        }
    }

    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings, Class<?> group) {
        if (type instanceof TypeVariable<?> variable) {
            Type bound = bindings.get(variable);
            if (bound == null || bound instanceof TypeVariable<?>) {
                throw new IllegalArgumentException("Группа " + group.getSimpleName() + ": тип " + variable.getName()
                        + " геттера не выводится — укажите аргумент типа у предка группы");
            }
            return bound;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments().clone();
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = substitute(arguments[i], bindings, group);
            }
            return new Resolved((Class<?>) parameterized.getRawType(), arguments);
        }
        return type;
    }

    /**
     * Параметризованный тип после подстановки.
     *
     * @param raw       класс
     * @param arguments аргументы типа
     */
    private record Resolved(Class<?> raw, Type[] arguments) implements ParameterizedType {

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return raw.getDeclaringClass();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType that && raw.equals(that.getRawType())
                    && Objects.equals(getOwnerType(), that.getOwnerType())
                    && Arrays.equals(arguments, that.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(arguments) ^ raw.hashCode();
        }

        @Override
        public String toString() {
            return raw.getTypeName() + Arrays.toString(arguments).replace('[', '<').replace(']', '>');
        }
    }
}
