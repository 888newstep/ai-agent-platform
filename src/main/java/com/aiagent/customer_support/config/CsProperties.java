package com.aiagent.customer_support.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "cs.import")
public class CsProperties {

    private String dataDir = "";
    private int batchSize = 18;
}
