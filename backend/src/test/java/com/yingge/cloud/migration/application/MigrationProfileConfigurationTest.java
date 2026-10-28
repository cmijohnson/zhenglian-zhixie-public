package com.yingge.cloud.migration.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationProfileConfigurationTest {
    @Test
    void migrationProfileUsesLazyInitializationToAvoidServletOnlyBeans() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-migration.yml"));

        assertThat(yaml.getObject())
                .isNotNull()
                .containsEntry("spring.main.lazy-initialization", true);
    }
}
