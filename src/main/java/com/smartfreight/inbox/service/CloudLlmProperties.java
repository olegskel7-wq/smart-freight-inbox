package com.smartfreight.inbox.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The cloud LLM a {@code llm.provider=cloud} tenant uses (ROADMAP LLM strategy, L2b). Defaults are the
 * configuration measured in L1 ({@code docs/ops/2026-11-10-L1-model-benchmark.md}): Claude Haiku 5.5 for
 * every role, effort {@code low} for the one-word labels and identifiers, {@code medium} for the reply draft.
 *
 * @param shortModel model for classification, extraction, document detection and inbox-query routing/phrasing
 * @param draftModel model for the customer reply draft (switch to {@code claude-sonnet-5-5} by property)
 * @param prices     USD per million input / output tokens per model, for {@code smartfreight_llm_cost_usd_total};
 *                   a model missing here is metered by tokens only
 */
@ConfigurationProperties(prefix = "llm.cloud")
public record CloudLlmProperties(String shortModel, String draftModel, Map<String, Price> prices) {

    public CloudLlmProperties {
        shortModel = shortModel == null || shortModel.isBlank() ? "claude-haiku-5-5" : shortModel;
        draftModel = draftModel == null || draftModel.isBlank() ? "claude-haiku-5-5" : draftModel;
        prices = prices == null ? Map.of() : Map.copyOf(prices);
    }

    /** USD per million tokens. */
    public record Price(BigDecimal input, BigDecimal output) {
    }
}
