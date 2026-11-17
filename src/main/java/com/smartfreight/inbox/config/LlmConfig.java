package com.smartfreight.inbox.config;

import com.smartfreight.inbox.service.CloudLlmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the cloud LLM settings ({@code llm.cloud.*}) — see {@link CloudLlmProperties}. */
@Configuration
@EnableConfigurationProperties(CloudLlmProperties.class)
public class LlmConfig {
}
