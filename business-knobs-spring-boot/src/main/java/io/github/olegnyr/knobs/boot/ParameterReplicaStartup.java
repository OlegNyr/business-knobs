package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.impl.ParameterReplicaLoader;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Читает реплику параметров после создания всех бинов и до старта жизненного цикла — то есть до
 * старта слушателей Kafka: топик дочитывается уже поверх реплики.
 * Связанные к этому моменту {@code @ConfigurationProperties} видели файл; ключи, где реплика с
 * ним разошлась, уходят событием смены.
 */
class ParameterReplicaStartup implements SmartInitializingSingleton {

    private final ParameterReplicaLoader loader;

    ParameterReplicaStartup(ParameterReplicaLoader loader) {
        this.loader = loader;
    }

    @Override
    public void afterSingletonsInstantiated() {
        loader.load();
    }
}
