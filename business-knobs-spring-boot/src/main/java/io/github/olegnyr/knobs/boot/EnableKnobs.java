package io.github.olegnyr.knobs.boot;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Регистрирует бинами интерфейсы с {@code @Knob} из указанных пакетов.
 * Без пакетов сканируется пакет аннотированного класса. Каждая группа — один бин своего
 * типа; геттеры читают через {@code Binder} в слой параметров при каждом вызове.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(ParameterGroupRegistrar.class)
public @interface EnableKnobs {

    /**
     * Пакеты для скана.
     *
     * @return имена пакетов
     */
    String[] basePackages() default {};

    /**
     * Классы, чьи пакеты сканируются — безопаснее строк при переименовании.
     *
     * @return классы-якоря
     */
    Class<?>[] basePackageClasses() default {};
}
