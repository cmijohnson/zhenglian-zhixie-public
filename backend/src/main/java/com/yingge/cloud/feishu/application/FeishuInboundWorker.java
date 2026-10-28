package com.yingge.cloud.feishu.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.feishu.long-connection-enabled", havingValue = "true")
public class FeishuInboundWorker {
    private final FeishuInboundProcessor processor;

    public FeishuInboundWorker(FeishuInboundProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(initialDelayString = "${app.feishu.inbound-run-delay-ms:5000}",
            fixedDelayString = "${app.feishu.inbound-run-delay-ms:5000}")
    public void run() {
        for (UUID inboxId : processor.ready(20)) {
            try {
                processor.processOne(inboxId);
            } catch (RuntimeException exception) {
                processor.recordFailure(inboxId, exception);
            }
        }
    }
}
