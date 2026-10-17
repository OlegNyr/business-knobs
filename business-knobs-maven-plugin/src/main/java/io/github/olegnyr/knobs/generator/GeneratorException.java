package io.github.olegnyr.knobs.generator;

/** Исходный файл параметров нельзя собрать: сообщение называет ключ параметра и причину. */
public class GeneratorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Отказ генерации.
     *
     * @param message ключ и причина
     */
    public GeneratorException(String message) {
        super(message);
    }
}
