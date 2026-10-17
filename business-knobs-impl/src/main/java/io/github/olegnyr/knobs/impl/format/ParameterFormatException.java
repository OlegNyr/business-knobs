package io.github.olegnyr.knobs.impl.format;

/**
 * Файл стенда или запись топика нарушает формат контракта.
 *
 * <p>Сообщение называет ключ параметра и причину и никогда не цитирует значение: значения
 * параметров уходят в лог только ключами.
 */
public class ParameterFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Нарушение формата.
     *
     * @param message ключ и причина, без значения
     */
    public ParameterFormatException(String message) {
        super(message);
    }
}
