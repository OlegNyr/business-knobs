package io.github.olegnyr.knobs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Хвост ключа параметра для метода группы {@link Knob}, когда он не выводится из имени
 * метода: вложенные части ({@code segment.call-enabled}) либо имя, которое методом не назвать.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface KnobKey {

    /**
     * Хвост ключа относительно префикса группы.
     *
     * @return хвост, например {@code segment.call-enabled}
     */
    String value();
}
