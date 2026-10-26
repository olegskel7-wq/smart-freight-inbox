package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Owns the sign-off of every outgoing reply.
 *
 * <p>Drafts are written before anyone is assigned, so the model cannot know who will send them —
 * left to itself it signs off with "[Your Name]", an invented job title and an invented company name.
 * Instead the model is told not to sign at all ({@link #draftingInstruction}), any sign-off it writes
 * anyway is cut ({@link #stripSignOff}), and the real signature — the sending operator's name from
 * their Keycloak token plus the tenant's registered name — is appended only to the outgoing MIME text
 * ({@link #sign}). {@code draft_reply} and {@code ai_original_draft} never contain the signature, so
 * the AI-acceptance KPI and the feedback loop do not count it as an operator edit.
 */
@Service
public class ReplySignatureService {

    /** Closing line of every appended signature. */
    static final String CLOSING = "Kind regards,";

    /** Name line when no operator is known (auto-send, Gmail drafts, a token without a name). */
    static final String TEAM_LINE = "Customer Service Team";

    /** How far from the end a sign-off may start — a closing further up is part of the body. */
    private static final int MAX_SIGN_OFF_TAIL = 8;

    /** Lines after a closing are name/title/company/phone — longer ones mean it was not a sign-off. */
    private static final int MAX_SIGNATURE_LINE_CHARS = 80;

    /** A line that on its own closes a letter ("Best regards", "Yours sincerely", "Thanks"), trailing punctuation removed. */
    private static final Pattern CLOSING_LINE = Pattern.compile(
            "(?i)(?:(?:best|kind|warm|warmest|with best|with kind|thanks and)\\s+)?(?:regards|wishes)"
            + "|(?:yours\\s+)?(?:sincerely|faithfully|truly)(?:\\s+yours)?"
            + "|(?:many\\s+)?thanks(?:\\s+again)?|thank\\s+you(?:\\s+again)?|best|cheers");

    /** A bare template placeholder line such as "[Your Name]" or "[Company]". */
    private static final Pattern PLACEHOLDER_LINE = Pattern.compile("^\\[[^\\]]{1,40}]$");

    private final TenantRepository tenantRepository;

    public ReplySignatureService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * Prompt suffix for every drafting call: who the reply is from, and that it must not be signed.
     * Appended (not substituted) so tenant-customised stored prompts get it without a migration.
     */
    public String draftingInstruction() {
        String company = companyName().map(name -> "You are writing on behalf of " + name + ". ").orElse("");
        return company + "Do not end the email with a sign-off, a name, a job title or a company signature: "
                + "the sender's signature is added automatically when the email is sent.";
    }

    /**
     * The outgoing text: the reply body with any trailing sign-off replaced by the real signature.
     * The operator's own trailing sign-off is replaced too, so a reply is never signed twice.
     *
     * @param body         the reply as stored in {@code draft_reply}
     * @param operatorName the sending operator's display name, or null when no operator is known
     */
    public String sign(String body, String operatorName) {
        String text = stripSignOff(body == null ? "" : body);
        String name = operatorName == null || operatorName.isBlank() ? TEAM_LINE : operatorName.trim();
        StringBuilder sb = new StringBuilder(text);
        if (!text.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(CLOSING).append('\n').append(name);
        companyName().ifPresent(company -> sb.append('\n').append(company));
        return sb.toString();
    }

    /**
     * Cuts a trailing sign-off block — a closing line ("Best regards,") followed only by short,
     * non-sentence signature lines (name, title, company, phone), within the last
     * {@value #MAX_SIGN_OFF_TAIL} lines — and any trailing "[Your Name]"-style placeholder lines.
     * Scanning stops at the first sentence from the end, so a "Thanks!" further up the body is
     * never mistaken for a sign-off. Text without a sign-off is returned trimmed but unchanged.
     */
    public static String stripSignOff(String text) {
        List<String> lines = Arrays.asList(text.strip().split("\\R", -1));
        int end = lines.size();
        for (int i = lines.size() - 1; i >= Math.max(0, lines.size() - MAX_SIGN_OFF_TAIL); i--) {
            String line = lines.get(i).strip();
            if (CLOSING_LINE.matcher(line.replaceAll("[\\s,.!]+$", "")).matches()) {
                end = i;
            } else if (line.length() > MAX_SIGNATURE_LINE_CHARS || line.matches(".*[.?!:;]$")) {
                break;
            }
        }
        while (end > 0 && (lines.get(end - 1).isBlank()
                || PLACEHOLDER_LINE.matcher(lines.get(end - 1).strip()).matches())) {
            end--;
        }
        return String.join("\n", lines.subList(0, end)).strip();
    }

    /** Operator display name from a Keycloak token: {@code name}, else given + family name, else null. */
    public static String displayName(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        String full = (nullToEmpty(jwt.getClaimAsString("given_name")) + " "
                + nullToEmpty(jwt.getClaimAsString("family_name"))).trim();
        return full.isEmpty() ? null : full;
    }

    /** The current tenant's registered name, if a tenant context is active. */
    private Optional<String> companyName() {
        Long tenantId = TenantContext.tenantIdOrNull();
        if (tenantId == null) {
            return Optional.empty();
        }
        return tenantRepository.findById(tenantId).map(Tenant::getName).filter(n -> !n.isBlank());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
