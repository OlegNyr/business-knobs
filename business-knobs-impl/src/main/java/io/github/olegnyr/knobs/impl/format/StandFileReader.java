package io.github.olegnyr.knobs.impl.format;

import io.github.olegnyr.knobs.impl.model.StandFile;

import java.io.InputStream;

/**
 * Чтение сгенерированного файла стенда. Настройки разбора — {@link ParameterRecordReader}:
 * незнакомое поле пропускается, незнакомое значение {@code type} или {@code kind} —
 * несовместимое изменение контракта, и такой файл не читается. Битое содержимое —
 * {@link ParameterFormatException} с ключом и причиной.
 */
public final class StandFileReader {

    private StandFileReader() {
    }

    /**
     * Читает файл стенда.
     *
     * @param in содержимое файла в JSON
     * @return файл стенда
     * @throws ParameterFormatException файл нарушает формат контракта
     */
    public static StandFile read(InputStream in) {
        return ParameterFormat.check(ParameterRecordReader.MAPPER.readValue(in, StandFile.class));
    }
}
