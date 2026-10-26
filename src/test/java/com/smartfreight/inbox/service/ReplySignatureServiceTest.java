package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.Tenant;
import com.smartfreight.inbox.repository.TenantRepository;
import com.smartfreight.inbox.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplySignatureServiceTest {

    private static final String BODY = "Good morning Tom,\n\nContainer CMAU5551234 is in transit.";

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final ReplySignatureService service = new ReplySignatureService(tenantRepository);

    private void tenantNamed(String name) {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName(name);
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(tenant));
    }

    // Реальный черновик демо-тикета 118: плейсхолдер, выдуманные должность и компания.
    @Test
    void stripSignOff_removesPlaceholderSignatureBlock() {
        String draft = BODY + "\n\nPlease let us know if you have any other questions.\n\nBest regards,\n"
                + "[Your Name]\nCustomer Service Representative\nFelixstowe Freight Logistics";

        assertEquals(BODY + "\n\nPlease let us know if you have any other questions.",
                ReplySignatureService.stripSignOff(draft));
    }

    @Test
    void stripSignOff_handlesClosingVariantsAndStackedClosings() {
        assertEquals(BODY, ReplySignatureService.stripSignOff(BODY + "\n\nYours sincerely,\nJane"));
        assertEquals(BODY, ReplySignatureService.stripSignOff(BODY + "\n\nKind Regards"));
        assertEquals(BODY, ReplySignatureService.stripSignOff(BODY + "\n\nThanks again,\nBest regards,\nJ. Smith"));
        assertEquals(BODY, ReplySignatureService.stripSignOff(BODY + "\n\nMany thanks!\r\n\r\n[Your Name]\r\n"));
    }

    // "Ltd." ends in a full stop but is a signature line — the closing above it must still be found.
    @Test
    void stripSignOff_companyLineWithFullStopIsStillSignature() {
        assertEquals(BODY, ReplySignatureService.stripSignOff(
                BODY + "\n\nBest regards,\n[Your Name]\nFelixstowe Freight Solutions Ltd."));
    }

    // Trailing placeholder lines are dropped even without a closing line.
    @Test
    void stripSignOff_dropsTrailingPlaceholderWithoutClosing() {
        assertEquals(BODY, ReplySignatureService.stripSignOff(BODY + "\n\n[Your Name]\n[Company]"));
    }

    // A "Thanks!" further up must not cut the sentences that follow it.
    @Test
    void stripSignOff_keepsBodyWhenClosingWordIsFollowedBySentences() {
        String draft = "Hi Tom,\n\nThanks!\n\nThe container is at customs.\nWe will update you shortly.";

        assertEquals(draft, ReplySignatureService.stripSignOff(draft));
    }

    @Test
    void stripSignOff_keepsSentenceStartingWithClosingWord() {
        String draft = BODY + "\nThank you for your patience while we chase HMRC";

        assertEquals(draft, ReplySignatureService.stripSignOff(draft));
    }

    @Test
    void sign_appendsOperatorAndTenantName() {
        tenantNamed("Felixstowe Freight Solutions Ltd");

        String signed = TenantContext.callAs(1L, () -> service.sign(BODY + "\n\nBest regards,\n[Your Name]", "Jane Doe"));

        assertEquals(BODY + "\n\nKind regards,\nJane Doe\nFelixstowe Freight Solutions Ltd", signed);
    }

    // Auto-send / Gmail draft: no operator → team line; no tenant context → no company line.
    @Test
    void sign_withoutOperatorOrTenant_usesTeamLineOnly() {
        assertEquals(BODY + "\n\nKind regards,\nCustomer Service Team", service.sign(BODY, null));
        assertEquals("Kind regards,\nCustomer Service Team", service.sign(null, " "));
    }

    @Test
    void draftingInstruction_namesTenantWhenKnown() {
        tenantNamed("Felixstowe Freight Solutions Ltd");

        assertTrue(TenantContext.callAs(1L, service::draftingInstruction)
                .startsWith("You are writing on behalf of Felixstowe Freight Solutions Ltd. Do not end"));
        assertTrue(service.draftingInstruction().startsWith("Do not end the email with a sign-off"));
    }

    @Test
    void displayName_prefersNameClaimThenGivenFamily() {
        assertEquals("Jane Doe", ReplySignatureService.displayName(jwt("name", "Jane Doe")));
        assertEquals("Jane Doe", ReplySignatureService.displayName(
                jwtBuilder().claim("given_name", "Jane").claim("family_name", "Doe").build()));
        assertNull(ReplySignatureService.displayName(jwt("preferred_username", "jane@x.com")));
        assertNull(ReplySignatureService.displayName(null));
    }

    private static Jwt jwt(String claim, String value) {
        return jwtBuilder().claim(claim, value).build();
    }

    private static Jwt.Builder jwtBuilder() {
        return Jwt.withTokenValue("t").header("alg", "none").subject("u1");
    }
}
