package io.github.olegnyr.knobs.impl.group;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;

/**
 * Группа параметров на абстрактном классе: ByteBuddy строит подкласс, где каждый
 * абстрактный метод уходит в тот же {@link InvocationHandler}, что и у интерфейса; конкретные
 * методы класса остаются как написаны. Класс загружается в пакет и загрузчик исходного типа через
 * {@code Lookup}, иначе package-private абстрактный метод не переопределить. Абстрактный метод,
 * вызванный из конструктора класса, обработчика ещё не видит: геттеры группы в конструкторе не
 * зовите.
 *
 * <p>Отдельный класс намеренно: только он ссылается на {@code net.bytebuddy}, и без зависимости
 * JVM его не загружает — {@link ParameterGroups} проверяет наличие ByteBuddy до обращения сюда.
 */
final class ClassGroups {

    /** Поле подкласса с обработчиком: подкласс один на тип, обработчик — свой у каждой группы. */
    private static final String HANDLER = "knobs$handler";

    /**
     * Подкласс строится один раз на тип: без кэша каждое создание группы определяло бы новый
     * класс, и Metaspace рос бы на каждом {@code Knobs.group()}.
     */
    private static final ClassValue<Class<?>> SUBCLASSES = new ClassValue<>() {
        @Override
        protected Class<?> computeValue(Class<?> type) {
            return subclass(type);
        }
    };

    private ClassGroups() {
    }

    // PMD.AvoidAccessibilityAlteration: подкласс повторяет видимость конструктора класса
    // (package-private, protected), а создаёт его библиотека из другого пакета; поле обработчика
    // закрытое.
    @SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "unchecked"})
    static <T> T create(Class<T> type, Constructor<T> constructor, InvocationHandler handler) {
        Class<? extends T> subclass = (Class<? extends T>) SUBCLASSES.get(type);
        try {
            Constructor<? extends T> subclassConstructor = subclass.getDeclaredConstructor(
                    constructor.getParameterTypes());
            subclassConstructor.setAccessible(true);
            T instance = subclassConstructor.newInstance();
            Field field = subclass.getDeclaredField(HANDLER);
            field.setAccessible(true);
            field.set(instance, handler);
            return instance;
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Группа параметров " + type.getName()
                    + ": конструктор класса отказал — " + e.getCause().getClass().getName(), e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Группа параметров " + type.getName() + ": подкласс не создан — "
                    + e.getClass().getName(), e);
        }
    }

    // PMD.UseProperClassLoader: подкласс обязан жить в загрузчике исходного типа — иначе его
    // package-private абстрактный метод не переопределить.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static Class<?> subclass(Class<?> type) {
        try {
            return new ByteBuddy()
                    .subclass(type)
                    .defineField(HANDLER, InvocationHandler.class, Visibility.PRIVATE)
                    .method(ElementMatchers.isAbstract())
                    .intercept(InvocationHandlerAdapter.toField(HANDLER))
                    .make()
                    .load(type.getClassLoader(),
                            ClassLoadingStrategy.UsingLookup.of(
                                    MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                            )
                    )
                    .getLoaded();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Группа параметров " + type.getName()
                    + ": пакет закрыт для подкласса — откройте модуль либо объявите группу интерфейсом", e);
        }
    }
}
