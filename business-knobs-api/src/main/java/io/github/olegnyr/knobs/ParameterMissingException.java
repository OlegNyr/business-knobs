package io.github.olegnyr.knobs;

/**
 * У параметра нет значения ни в одном источнике, а метод группы не возвращает {@code Optional}.
 * Значение по умолчанию живёт в {@code knobs.yaml}: отсутствие — ошибка
 * каталога, а не повод искать его в окружении.
 */
public class ParameterMissingException extends RuntimeException {

    private final String key;

    /**
     * Исключение по ключу параметра.
     *
     * @param key ключ параметра
     */
    public ParameterMissingException(String key) {
        super("Параметр " + key + ": значения нет ни в одном источнике");
        this.key = key;
    }

    /**
     * Ключ параметра без значения.
     *
     * @return ключ
     */
    public String getKey() {
        return key;
    }
}
