package io.github.olegnyr.knobs.impl.group;

import io.github.olegnyr.knobs.Knob;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Сборка прокси группы параметров: тип с {@link Knob} проверяется один
 * раз при создании, ключи методов вычисляются тогда же; чтение — на каждом вызове через
 * {@link ParameterReader}.
 *
 * <p>Интерфейс реализует {@link Proxy} JDK. Абстрактный класс наследует подкласс, который строит
 * ByteBuddy ({@link ClassGroups}): зависимость необязательная, без неё класс отбивается при создании
 * с подсказкой — объявить группу интерфейсом либо добавить {@code net.bytebuddy:byte-buddy}.
 */
public final class ParameterGroups {

    private static final String BYTE_BUDDY = "net.bytebuddy.ByteBuddy";

    private ParameterGroups() {
    }

    /**
     * Прокси группы над читателем.
     *
     * @param type   интерфейс либо нефинальный класс с {@link Knob}
     * @param reader откуда брать значения
     * @param <T>    тип группы
     * @return прокси, реализующий интерфейс либо наследующий класс
     * @throws IllegalArgumentException без аннотации; метод с аргументами или без результата;
     *                                  {@code final}-класс; класс без конструктора без аргументов
     * @throws IllegalStateException    класс при отсутствии ByteBuddy на classpath
     */
    public static <T> T create(Class<T> type, ParameterReader reader) {
        return create(type, reader, byteBuddyPresent());
    }

    // PMD.UseProperClassLoader: прокси интерфейса определяется в его загрузчике намеренно — иначе
    // интерфейс модуля сервиса из другого загрузчика прокси не увидит.
    @SuppressWarnings("PMD.UseProperClassLoader")
    static <T> T create(Class<T> type, ParameterReader reader, boolean byteBuddyPresent) {
        Knob group = type.getAnnotation(Knob.class);
        if (group == null) {
            throw new IllegalArgumentException("Группа параметров " + type.getName() + " без @Knob");
        }
        if (type.isInterface()) {
            Map<Method, GroupMethod> methods = new LinkedHashMap<>();
            for (Method method : type.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) && !method.isDefault()) {
                    methods.put(method, GroupMethod.of(group.value(), method, type));
                }
            }
            ParameterGroupHandler parameterGroupHandler
                    = new ParameterGroupHandler(type, group.value(), methods, reader);
            return type.cast(Proxy
                    .newProxyInstance(type.getClassLoader(),
                            new Class<?>[]{type},
                            parameterGroupHandler));
        }
        if (Modifier.isFinal(type.getModifiers())) {
            throw new IllegalArgumentException("Группа параметров " + type.getName()
                    + " — final-класс: подкласс не построить, снимите final либо объявите интерфейс");
        }
        if (!byteBuddyPresent) {
            throw new IllegalStateException("Группа параметров " + type.getName() + " — класс: для подкласса "
                    + "нужен net.bytebuddy:byte-buddy на classpath; добавьте зависимость либо объявите группу "
                    + "интерфейсом");
        }
        Constructor<T> constructor = noArgConstructor(type);
        Map<Method, GroupMethod> methods = new LinkedHashMap<>();
        for (Method method : abstractMethods(type)) {
            methods.put(method, GroupMethod.of(group.value(), method, type));
        }
        ParameterGroupHandler handler = new ParameterGroupHandler(type, group.value(), methods, reader);
        return ClassGroups.create(type, constructor, handler);
    }

    /**
     * Абстрактные методы класса и его предков: публичные через {@code getMethods()}, остальные —
     * по объявлениям вверх по иерархии; переопределённый ниже метод не считается.
     */
    private static Iterable<Method> abstractMethods(Class<?> type) {
        Map<String, Method> found = new LinkedHashMap<>();
        for (Method method : type.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers())) {
                found.putIfAbsent(signature(method), method);
            }
        }
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isAbstract(method.getModifiers()) && !Modifier.isPublic(method.getModifiers())) {
                    found.putIfAbsent(signature(method), method);
                }
            }
        }
        return found.values();
    }

    private static String signature(Method method) {
        return method.getName() + Arrays.toString(method.getParameterTypes());
    }

    private static <T> Constructor<T> noArgConstructor(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            if (Modifier.isPrivate(constructor.getModifiers())) {
                throw new IllegalArgumentException("Группа параметров " + type.getName()
                        + ": конструктор без аргументов private — подклассу он недоступен");
            }
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Группа параметров " + type.getName()
                    + ": нужен конструктор без аргументов — подкласс создаётся им", e);
        }
    }

    // PMD.UseProperClassLoader: ByteBuddy ищется загрузчиком библиотеки — им же ClassGroups и
    // загрузит net.bytebuddy; загрузчик потока тут ни при чём.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static boolean byteBuddyPresent() {
        try {
            Class.forName(BYTE_BUDDY, false, ParameterGroups.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
