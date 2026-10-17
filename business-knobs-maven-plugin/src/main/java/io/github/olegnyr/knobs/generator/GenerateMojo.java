package io.github.olegnyr.knobs.generator;

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Цель {@code generate}: из исходного {@code knobs.yaml} сервиса пишет файл на каждый стенд —
 * {@code knobs/knobs-<стенд>.json} в каталоге вывода — и добавляет каталог вывода в
 * ресурсы проекта. Файлы стендов попадают в jar сервиса там, где их ищет библиотека, без
 * настройки {@code <resources>}; в репозиторий они не коммитятся.
 *
 * <pre>{@code
 * <plugin>
 *     <groupId>io.github.olegnyr</groupId>
 *     <artifactId>business-knobs-maven-plugin</artifactId>
 *     <version>...</version>
 *     <executions>
 *         <execution>
 *             <goals>
 *                 <goal>generate</goal>
 *             </goals>
 *         </execution>
 *     </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    /**
     * Каталог внутри ресурсов, где библиотека ищет файлы стендов.
     */
    static final String STAND_FILES = "knobs";

    /**
     * Проект, в чьи ресурсы добавляется каталог вывода.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Исходный файл параметров сервиса.
     */
    @Parameter(property = "knobs.generate.source", defaultValue = "${project.basedir}/src/main/knobs/knobs.yaml")
    private File source;

    /**
     * Корень сгенерированных ресурсов; файлы стендов ложатся в его подкаталог {@code knobs}.
     */
    @Parameter(property = "knobs.generate.outputDirectory",
            defaultValue = "${project.build.directory}/generated-resources/knobs")
    private File outputDirectory;

    /**
     * Версия сборки — поле {@code version} файлов стендов: по ней админка отличает файл старше
     * загруженного. Своего свойства нет намеренно: свойство pom с тем же именем (например, версия
     * библиотеки) молча попало бы в каждый файл стенда. Меняется только в {@code <configuration>}.
     */
    @Parameter(defaultValue = "${project.version}")
    private String version;

    /**
     * Пропустить генерацию.
     */
    @Parameter(property = "knobs.generate.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoFailureException {
        if (skip) {
            getLog().info("Генерация файлов стендов пропущена (knobs.generate.skip)");
            return;
        }
        Path sourcePath = source.toPath();
        if (!Files.isRegularFile(sourcePath)) {
            throw new MojoFailureException("Нет исходного файла параметров: " + sourcePath);
        }
        List<Path> written;
        try {
            written = StandFileGenerator.write(sourcePath, outputDirectory.toPath().resolve(STAND_FILES), version);
        } catch (GeneratorException e) {
            throw new MojoFailureException("Исходный файл параметров " + sourcePath + " не собирается: "
                    + e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoFailureException("Файлы стендов не записаны из " + sourcePath + ": "
                    + e.getClass().getName(), e);
        }
        Resource resource = new Resource();
        resource.setDirectory(outputDirectory.getPath());
        project.addResource(resource);
        getLog().info("Файлы стендов из " + sourcePath + ": " + written.size() + " шт. в " + outputDirectory);
    }

    MavenProject project() {
        return project;
    }

    void setProject(MavenProject mavenProject) {
        this.project = mavenProject;
    }

    void setSource(File file) {
        this.source = file;
    }

    void setOutputDirectory(File directory) {
        this.outputDirectory = directory;
    }

    void setVersion(String buildVersion) {
        this.version = buildVersion;
    }

    void setSkip(boolean skipped) {
        this.skip = skipped;
    }
}
