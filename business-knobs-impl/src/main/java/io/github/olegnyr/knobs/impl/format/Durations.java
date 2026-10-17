package io.github.olegnyr.knobs.impl.format;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Длительность так, как её пишут в настройках Spring: {@code 500ms}, {@code 10s}, {@code 5m}, число
 * без единицы — миллисекунды, либо ISO-8601 ({@code PT10S}). Своя, а не {@code DurationStyle}
 * Spring Boot: библиотека работает и без Spring.
 */
public final class Durations {

    private static final Pattern SIMPLE = Pattern.compile("^([+-]?\\d+)([a-zA-Z]{0,2})$");

    private static final Pattern ISO = Pattern.compile("^[+-]?[pP].*$");

    private static final Map<String, ChronoUnit> UNITS = Map.of(
            "ns", ChronoUnit.NANOS,
            "us", ChronoUnit.MICROS,
            "ms", ChronoUnit.MILLIS,
            "s", ChronoUnit.SECONDS,
            "m", ChronoUnit.MINUTES,
            "h", ChronoUnit.HOURS,
            "d", ChronoUnit.DAYS);

    private Durations() {
    }

    /**
     * Читает длительность.
     *
     * @param text запись длительности
     * @return длительность
     * @throws IllegalArgumentException запись не читается; текст значения в сообщение не попадает
     */
    // PMD.PreserveStackTrace: причина не передаётся намеренно — текст NumberFormatException и
    // DateTimeParseException цитирует значение; отказ называет только суть.
    @SuppressWarnings("PMD.PreserveStackTrace")
    public static Duration parse(String text) {
        String trimmed = text.trim();
        Matcher simple = SIMPLE.matcher(trimmed);
        if (simple.matches()) {
            String unit = simple.group(2).toLowerCase(Locale.ROOT);
            ChronoUnit chronoUnit = unit.isEmpty() ? ChronoUnit.MILLIS : UNITS.get(unit);
            if (chronoUnit == null) {
                throw new IllegalArgumentException("Неизвестная единица длительности");
            }
            try {
                return Duration.of(Long.parseLong(simple.group(1)), chronoUnit);
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException("Длительность за пределами допустимого");
            }
        }
        if (ISO.matcher(trimmed).matches()) {
            try {
                return Duration.parse(trimmed);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Длительность не читается как ISO-8601");
            }
        }
        throw new IllegalArgumentException("Длительность не читается");
    }
}
