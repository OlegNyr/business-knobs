# business-knobs-maven-plugin

Плагин Maven, который на сборке сервиса превращает исходный `knobs.yaml` разработчика в файлы
стендов `knobs/knobs-<стенд>.json` и кладёт их в ресурсы проекта — туда, где их ищет библиотека.

## Цель

| | |
|---|---|
| Координаты | `io.github.olegnyr:business-knobs-maven-plugin` |
| Goal | `generate` (goal-prefix `knobs` → `mvn knobs:generate`); фаза по умолчанию `generate-resources` |
| Справка | `mvn knobs:help -Ddetail` |

## Параметры

| Параметр | Свойство | По умолчанию | Описание |
|---|---|---|---|
| `source` | `knobs.generate.source` | `${project.basedir}/src/main/knobs/knobs.yaml` | Исходный файл параметров. Нет файла — сборка падает с путём. |
| `outputDirectory` | `knobs.generate.outputDirectory` | `${project.build.directory}/generated-resources/knobs` | Корень сгенерированных ресурсов; файлы стендов — в его подкаталоге `knobs`. Каталог сам добавляется в ресурсы проекта. |
| `version` | — (только `<configuration>`) | `${project.version}` | Поле `version` файлов стендов: по нему админка отличает файл старше загруженного. Своего свойства нет, чтобы свойство pom сервиса с тем же именем не попало в файлы. |
| `skip` | `knobs.generate.skip` | `false` | Пропустить генерацию. |

## Использование

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.github.olegnyr</groupId>
      <artifactId>business-knobs-maven-plugin</artifactId>
      <version>0.1.0</version>
      <executions>
        <execution>
          <goals>
            <goal>generate</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

Настраивать `<resources>` не нужно. Сгенерированные файлы в репозиторий не коммитятся.

Ошибка в исходном файле валит сборку и называет ключ и причину — без цитаты значения:

```
[ERROR] Исходный файл параметров .../src/main/knobs/knobs.yaml не собирается: app.k: нет name строкой
```

Формат `knobs.yaml` — `doc/guide/knobs-example.yaml`, формат файла стенда — `doc/contract/stand-file.adoc`.
Живой пример подключения — модуль `business-knobs-example`.

## Без Maven

`io.github.olegnyr.knobs.generator.StandFileGenerator` — то же самое из командной строки
(`<knobs.yaml> <каталог> <версия>`) либо из кода (`StandFileGenerator.write(...)`), например в
сборке Gradle.
