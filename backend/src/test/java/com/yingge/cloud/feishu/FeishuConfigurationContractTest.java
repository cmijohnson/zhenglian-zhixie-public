package com.yingge.cloud.feishu;

import com.yingge.cloud.feishu.application.FeishuSyncWorker;
import com.yingge.cloud.feishu.application.FeishuInboundWorker;
import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;
import com.yingge.cloud.feishu.infrastructure.FeishuLongConnectionManager;
import com.yingge.cloud.feishu.infrastructure.FeishuSecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuConfigurationContractTest {
    @Test
    void runtimePropertiesUseTheApplicationYamlPrefix() throws Exception {
        Value secret = FeishuSecretCipher.class.getConstructors()[0]
                .getParameters()[0].getAnnotation(Value.class);
        Value baseUrl = FeishuBitableClient.class.getConstructors()[0]
                .getParameters()[0].getAnnotation(Value.class);
        Scheduled worker = FeishuSyncWorker.class.getMethod("processRequestedRuns")
                .getAnnotation(Scheduled.class);
        Scheduled inboundWorker = FeishuInboundWorker.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(secret.value()).isEqualTo("${app.feishu.master-key:}");
        assertThat(baseUrl.value()).isEqualTo("${app.feishu.base-url:https://open.feishu.cn}");
        assertThat(worker.fixedDelayString()).isEqualTo("${app.feishu.run-delay-ms:60000}");
        assertThat(inboundWorker.fixedDelayString()).isEqualTo("${app.feishu.inbound-run-delay-ms:5000}");
        ConditionalOnProperty longConnection = FeishuLongConnectionManager.class
                .getAnnotation(ConditionalOnProperty.class);
        assertThat(longConnection.name()).containsExactly("app.feishu.long-connection-enabled");
        assertThat(longConnection.havingValue()).isEqualTo("true");
        String yaml = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(yaml).contains("long-connection-enabled: ${FEISHU_LONG_CONNECTION_ENABLED:true}");
    }
}
