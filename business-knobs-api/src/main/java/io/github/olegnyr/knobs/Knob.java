package io.github.olegnyr.knobs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Группа параметров: интерфейс с геттерами, за которым стоит прокси библиотеки.
 *
 * <p>Каждый вызов геттера читает действующее значение заново — для пути пода, дополненного путём
 * обращения текущего потока ({@link ParameterContext}). Ключ параметра — префикс группы и хвост:
 * {@link KnobKey} метода либо его имя в kebab-case ({@code hopLimit()} → {@code hop-limit}).
 * Методы без аргументов; {@code Optional<T>} — «значения нет» отличимо; иной тип без значения —
 * {@link ParameterMissingException}. {@code default}-методы исполняются как есть.
 *
 * <p>Без Spring группу даёт {@code Knobs#group(Class)}; со Spring интерфейсы пакета
 * регистрируются бинами по {@code @EnableKnobs}.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Knob {

    /**
     * Префикс ключей группы без завершающей точки; пустой — ключ равен хвосту метода.
     *
     * @return префикс, например {@code app.routing}
     */
    String value();
}
