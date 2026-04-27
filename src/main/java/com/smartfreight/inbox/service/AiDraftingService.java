package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Atsakymo juodraščio generavimo paslauga naudojant Llama 3.1.
 *
 * Gauna laiško turinį ir konteinerio duomenis →
 * grąžina paruoštą juodraštį operatoriui.
 *
 * Jei Ollama nepasiekiamas arba grąžina tuščią atsakymą –
 * naudojamas statinis atsarginis juodraštis su duomenimis iš DB.
 */
@Service
public class AiDraftingService {

    private static final Logger log = LoggerFactory.getLogger(AiDraftingService.class);

    /** ETA datos formatavimas išvedimui (pvz.: "15 Apr 2025, 14:30"). */
    private static final DateTimeFormatter ETA_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    /** Juodraščio generavimo modelės pavadinimas – skaitomas iš application.yaml. */
    @Value("${ollama.drafting-model:llama3.1:8b}")
    private String draftingModel;

    private final WebClient ollamaWebClient;

    public AiDraftingService(WebClient ollamaWebClient) {
        this.ollamaWebClient = ollamaWebClient;
    }

    /**
     * Generuoja profesionalų atsakymo juodraštį kliento laišką.
     *
     * Vykdo POST /api/generate į Ollama su promtu, kuriame:
     * – laiško tekstas (iki 2000 simbolių)
     * – konteinerio duomenys (jei rasti)
     *
     * Modelė instrukuojama atsakyti ta pačia kalba kaip klientas
     * ir nenaudoti informacijos, kurios nėra konteinerio duomenyse.
     *
     * @param emailBody  kliento laiško tekstas
     * @param container  konteinerio duomenys (gali būti null jei nerastas)
     * @return sugeneruotas arba atsarginis juodraštis
     */
    public String generateDraft(String emailBody, Container container) {
        String containerContext = buildContainerContext(container);

        String prompt = """
                You are a professional customer service representative for a freight logistics company near Felixstowe port, UK.
                Write a concise, professional email reply to the customer's inquiry below.
                Use the container information provided. Be helpful, polite, and factual.
                Do NOT make up information that is not in the container data.
                Reply in the same language as the customer's email.

                Container information:
                """ + containerContext + """

                Customer's email:
                """ + emailBody.substring(0, Math.min(emailBody.length(), 2000)) + """

                Write only the email reply body (no subject line, no "Dear..." header unless natural).
                """;

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", draftingModel);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", false);

            log.debug("Sending drafting request to Ollama model {}", draftingModel);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaWebClient
                    .post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(150));

            if (response == null) {
                log.warn("Ollama returned null response during drafting");
                return buildFallbackDraft(container);
            }

            String draft = String.valueOf(response.getOrDefault("response", "")).trim();
            if (draft.isEmpty()) {
                log.warn("Ollama returned empty draft");
                return buildFallbackDraft(container);
            }

            log.info("Draft generated successfully ({} chars)", draft.length());
            return draft;

        } catch (Exception e) {
            log.error("Drafting call to Ollama failed: {}", e.getMessage());
            return buildFallbackDraft(container);
        }
    }

    /**
     * Sukuria tekstinį konteinerio duomenų bloką įterpimui į promptą.
     *
     * @param container konteinerio duomenys arba null
     * @return suformatuotas tekstas su konteinerio informacija
     */
    private String buildContainerContext(Container container) {
        if (container == null) {
            return "Container not found in our system.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Container number: ").append(container.getContainerNumber()).append("\n");
        sb.append("Status: ").append(container.getStatus() != null ? container.getStatus() : "Unknown").append("\n");

        if (container.getEta() != null) {
            sb.append("ETA: ").append(container.getEta().format(ETA_FORMATTER)).append("\n");
        } else {
            sb.append("ETA: Not available\n");
        }

        sb.append("Location: ").append(container.getLocation() != null ? container.getLocation() : "Unknown");
        return sb.toString();
    }

    /**
     * Atsarginis juodraštis – naudojamas kai Ollama nepasiekiamas.
     * Pateikia bazinę informaciją iš DB be AI generavimo.
     *
     * @param container konteinerio duomenys arba null
     * @return statinis juodraštis su turimais duomenimis
     */
    private String buildFallbackDraft(Container container) {
        if (container == null) {
            return """
                    Thank you for your enquiry.
                    Unfortunately, we were unable to locate the container in our system at this time.
                    Please contact our operations team for further assistance.
                    Kind regards,
                    Freight Operations Team
                    """;
        }

        return String.format("""
                Thank you for your enquiry regarding container %s.
                
                Current status: %s
                ETA: %s
                Location: %s
                
                Please do not hesitate to contact us if you require further information.
                Kind regards,
                Freight Operations Team
                """,
                container.getContainerNumber(),
                container.getStatus() != null ? container.getStatus() : "N/A",
                container.getEta() != null ? container.getEta().format(ETA_FORMATTER) : "N/A",
                container.getLocation() != null ? container.getLocation() : "N/A"
        );
    }
}
