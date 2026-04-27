package com.smartfreight.inbox.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Konteinerio numerio ištraukimo paslauga naudojant Qwen 2.5.
 *
 * Siunčia laiško turinį į Ollama (POST /api/generate) ir prašo grąžinti ISO 6346 numerį.
 * Blokuojantis {@code .block()} čia priimtinas: kvietimas vykdomas
 * servletų/IMAP darbo sraute, ne Netty event loop.
 *
 * Jei Ollama nepasiekiamas arba grąžina neaiškų atsakymą –
 * naudojamas regex atsarginis variantas tiesiogiai laiško tekste.
 */
@Service
public class AiExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AiExtractionService.class);

    /**
     * ISO 6346 konteinerio numerio šablonas: 4 didžiosios raidės + 7 skaitmenys.
     * Pvz.: MSCU1234567, TCKU9876543.
     * Naudojamas kaip atsarginis metodas kai LLM grąžina nesuprantamą atsakymą.
     */
    private static final Pattern CONTAINER_PATTERN =
            Pattern.compile("\\b([A-Z]{4}\\d{7})\\b");

    /** Ištraukimo modelės pavadinimas – skaitomas iš application.yaml. */
    @Value("${ollama.extraction-model:qwen2.5:7b}")
    private String extractionModel;

    private final WebClient ollamaWebClient;

    public AiExtractionService(WebClient ollamaWebClient) {
        this.ollamaWebClient = ollamaWebClient;
    }

    /**
     * Siunčia laiško turinį į Qwen 2.5 ir gauna konteinerio numerį.
     *
     * Logiką:
     * 1. Siunčiamas promptas į Ollama (LLM tiesiogiai grąžina tik numerį)
     * 2. Jei atsakymas tiksliai atitinka ISO 6346 – grąžinamas tiesiogiai
     * 3. Jei numeris yra atsakymo viduje – ištraukiamas regex
     * 4. Jei LLM nerado – bandomas regex tiesiai laiško tekste
     * 5. Bet kuri klaida → atsarginis regex
     *
     * @param emailBody laiško tekstinis turinys
     * @return konteinerio numeris (pvz.: "MSCU1234567") arba tuščia eilutė
     */
    public String extractContainerNumber(String emailBody) {
        if (emailBody == null || emailBody.isBlank()) {
            log.warn("Email body is empty, skipping extraction");
            return "";
        }

        // Promptas Qwen: prašome grąžinti TIK numerį, jokio papildomo teksto
        String prompt = """
                You are a logistics assistant. Extract the shipping container number from the email below.
                Container numbers follow ISO 6346 format: exactly 4 uppercase letters followed by 7 digits (e.g. MSCU1234567).
                Return ONLY the container number, nothing else. If no container number is found, return "NOT_FOUND".

                Email:
                """ + emailBody.substring(0, Math.min(emailBody.length(), 2000));

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", extractionModel);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", false);

            log.debug("Sending extraction request to Ollama model {}", extractionModel);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaWebClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(150));

            if (response == null) {
                log.warn("Ollama returned null response during extraction");
                return fallbackRegexExtraction(emailBody);
            }

            String rawResponse = String.valueOf(response.getOrDefault("response", "")).trim();
            log.info("Qwen raw response: '{}'", rawResponse);

            // Jei LLM grąžino švarų numerį – tikriname formatą
            if (CONTAINER_PATTERN.matcher(rawResponse).matches()) {
                log.info("Container number extracted by LLM: {}", rawResponse);
                return rawResponse;
            }

            // LLM galėjo grąžinti numerį sakinyje – bandome rasti šabloną atsakyme
            Matcher m = CONTAINER_PATTERN.matcher(rawResponse.toUpperCase());
            if (m.find()) {
                log.info("Container number extracted from LLM response via regex: {}", m.group(1));
                return m.group(1);
            }

            // LLM nerado numerio – bandome tiesioginį regex laiško tekste
            log.warn("LLM could not extract container number, trying regex fallback");
            return fallbackRegexExtraction(emailBody);

        } catch (Exception e) {
            log.error("Extraction call to Ollama failed: {}", e.getMessage());
            return fallbackRegexExtraction(emailBody);
        }
    }

    /**
     * Atsarginis metodas: ieškoma konteinerio numerio regex tiesiogiai laiško tekste.
     * Naudojama kai Ollama nepasiekiamas arba grąžino neaiškų atsakymą.
     *
     * @param body laiško tekstas
     * @return rastas konteinerio numeris arba tuščia eilutė
     */
    private String fallbackRegexExtraction(String body) {
        Matcher m = CONTAINER_PATTERN.matcher(body.toUpperCase());
        if (m.find()) {
            log.info("Container number found via regex fallback: {}", m.group(1));
            return m.group(1);
        }
        log.warn("No container number found in email body (LLM + regex both failed)");
        return "";
    }
}
