package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.impl.evaluation.ContextDimensions;
import io.github.olegnyr.knobs.impl.format.ParameterFormatException;
import io.github.olegnyr.knobs.impl.format.StandFileReader;
import io.github.olegnyr.knobs.impl.model.DimensionDeclaration;
import io.github.olegnyr.knobs.impl.model.StandFile;
import io.github.olegnyr.knobs.impl.snapshot.ParameterSet;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Файл стенда в сборке сервиса и снимок из него — без Spring.
 *
 * <p>Файл ищется по пути {@value #LOCATION} во всех местах classpath, как {@code classpath*:}:
 * стенд — тот из переданных, для которого файл есть. Нет такого — слоя нет; таких несколько либо
 * файл стенда лежит в двух местах classpath — отказ: стенд неоднозначен, а молча выбрать один
 * значит отдать значения, зависящие от порядка classpath. Отказ называет файл и класс причины,
 * но не цитирует содержимое.
 */
public final class StandFiles {

    /**
     * Путь файла стенда в classpath; {@code %s} — стенд.
     */
    public static final String LOCATION = "knobs/knobs-%s.json";

    private StandFiles() {
    }

    /**
     * Найденный файл стенда.
     *
     * @param description где лежит — для лога и отказа
     * @param file        прочитанный файл
     */
    public record Located(String description, StandFile file) {
    }

    /**
     * Ищет и читает файл одного из стендов.
     *
     * @param classLoader загрузчик сборки сервиса
     * @param stands      стенды-кандидаты: активные профили либо явно названный стенд
     * @return файл; пусто — ни для одного стенда файла нет
     * @throws IllegalStateException файлов несколько либо файл не читается
     */
    public static Optional<Located> locate(ClassLoader classLoader, List<String> stands) {
        List<URL> files = new ArrayList<>();
        for (String stand : stands) {
            files.addAll(resources(classLoader, LOCATION.formatted(stand)));
        }
        if (files.isEmpty()) {
            return Optional.empty();
        }
        if (files.size() > 1) {
            throw new IllegalStateException("Файлы параметров неоднозначны для профилей " + stands
                    + ", стенд неоднозначен: " + files.stream().map(URL::toString).toList());
        }
        URL file = files.getFirst();
        return Optional.of(new Located(file.toString(), read(file)));
    }

    /**
     * Снимок пода из файла стенда.
     *
     * @param located     файл стенда
     * @param podPath     путь пода: сервис и ЦОД
     * @param configured  измерения, заданные сервисом ({@code knobs.dimensions}); {@code null} —
     *                    не заданы
     * @param environment переменные окружения процесса для подстановок {@code ${ИМЯ}}
     * @param unresolved  куда сообщить ключ и имена неразрешённых переменных — без значений
     * @return хранилка снимка
     */
    public static ParameterStore store(Located located, ContextPath podPath, ContextDimensions configured,
                                       Function<String, String> environment,
                                       BiConsumer<String, Set<String>> unresolved) {
        ContextDimensions dimensions = dimensions(located, configured);
        return new ParameterStore(ParameterSet.fromStandFile(located.file(), dimensions, environment, unresolved),
                podPath);
    }

    /**
     * Строка предупреждения о параметре, у которого из файла нет значения: переменной нет либо её
     * значение не приводится к типу параметра. Значения переменных сюда не попадают.
     *
     * @param key       ключ параметра
     * @param variables имена неразрешённых переменных; пусто — переменные есть, но значение не
     *                  читается типом параметра
     * @return строка для лога
     */
    public static String unresolvedMessage(String key, Set<String> variables) {
        return variables.isEmpty()
                ? "Подстановка не приводится к типу параметра: key=" + key
                : "Подстановка не разрешилась: key=" + key + ", переменные=" + variables;
    }

    /**
     * Измерения пути снимка. Порядок задаёт файл стенда — секция {@code dimensions} без
     * {@code service}: те же измерения и в том же порядке, по которым генератор разбирал пути
     * правил. Настройка сервиса нужна, только когда файл измерений не объявляет; заданная и
     * расходящаяся с файлом — отказ на старте, а не молча другой выбор правил.
     *
     * @param located    файл стенда
     * @param configured измерения, заданные сервисом; {@code null} — не заданы
     * @return измерения в порядке точности
     * @throws IllegalStateException заданные измерения расходятся с файлом либо файл объявляет
     *                               измерение дважды
     */
    static ContextDimensions dimensions(Located located, ContextDimensions configured) {
        List<String> declared = located.file().getDimensions().stream()
                .map(DimensionDeclaration::getKey)
                .filter(key -> !ContextPath.SERVICE.equals(key))
                .toList();
        if (declared.isEmpty()) {
            return configured != null ? configured : ContextDimensions.DEFAULT;
        }
        ContextDimensions fromFile;
        try {
            fromFile = ContextDimensions.of(declared);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Измерения файла стенда " + located.description() + " негодны: "
                    + e.getMessage(), e);
        }
        if (configured != null && !configured.equals(fromFile)) {
            throw new IllegalStateException("Измерения knobs.dimensions " + configured
                    + " расходятся с измерениями файла стенда " + fromFile + " (" + located.description()
                    + "): порядок задаёт файл — задайте те же измерения в том же порядке либо уберите настройку");
        }
        return fromFile;
    }

    // PMD.PreserveStackTrace: причина не передаётся намеренно — её текст может цитировать
    // содержимое файла; в отказ уходит класс причины.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static List<URL> resources(ClassLoader classLoader, String location) {
        try {
            return Collections.list(classLoader.getResources(location));
        } catch (IOException e) {
            throw new IllegalStateException("Файлы параметров не найдены: " + location + ", причина: "
                    + e.getClass().getName());
        }
    }

    // PMD.PreserveStackTrace: см. resources — текст причины цитирует фрагмент файла.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static StandFile read(URL file) {
        try (InputStream in = file.openStream()) {
            return StandFileReader.read(in);
        } catch (ParameterFormatException e) {
            // Сообщение формата называет ключ и причину и значений не цитирует.
            throw new IllegalStateException("Файл параметров не прочитан: " + file + ", " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            // Текст причины не цитируется: разбор JSON приводит в сообщении фрагмент файла.
            throw new IllegalStateException("Файл параметров не прочитан: " + file + ", причина: "
                    + e.getClass().getName());
        }
    }
}
