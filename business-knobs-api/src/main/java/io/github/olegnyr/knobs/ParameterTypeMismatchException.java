package io.github.olegnyr.knobs;

/**
 * Значение параметра не приводится к типу метода группы: тип в каталоге один,
 * в интерфейсе — другой. Сообщение называет ключ и оба типа; значения в нём нет.
 */
public class ParameterTypeMismatchException extends RuntimeException {

    private final String key;

    /**
     * Исключение по ключу и типам.
     *
     * @param key      ключ параметра
     * @param expected тип метода группы
     * @param actual   тип значения параметра
     */
    public ParameterTypeMismatchException(String key, String expected, String actual) {
        super("Параметр " + key + ": метод ожидает " + expected + ", значение — " + actual);
        this.key = key;
    }

    /**
     * Ключ параметра.
     *
     * @return ключ
     */
    public String getKey() {
        return key;
    }
}
