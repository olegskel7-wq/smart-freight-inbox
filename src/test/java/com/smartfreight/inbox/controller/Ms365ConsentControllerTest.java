package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.service.Ms365ConsentService;
import com.smartfreight.inbox.service.Ms365ConsentService.ConsentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The public consent callback (V38): leg 1 (a sign-in code) is redeemed and continues to Microsoft's
 * admin consent; leg 2 and errors end on a self-contained page whose text is always escaped.
 */
class Ms365ConsentControllerTest {

    private Ms365ConsentService service;
    private Ms365ConsentController controller;

    @BeforeEach
    void setUp() {
        service = mock(Ms365ConsentService.class);
        controller = new Ms365ConsentController(service);
    }

    @Test
    void signInLeg_bindsAndContinuesToAdminConsent() {
        when(service.complete("code", "state")).thenReturn("dir-1");
        when(service.adminConsentUrl("dir-1")).thenReturn("https://login.microsoftonline.com/dir-1/v2.0/adminconsent?x=1");

        ResponseEntity<String> r = controller.callback("code", "state", null, null, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(r.getHeaders().getLocation()).hasToString("https://login.microsoftonline.com/dir-1/v2.0/adminconsent?x=1");
    }

    @Test
    void consentLeg_granted_endsOnTheSuccessPage_withoutTouchingTheService() {
        ResponseEntity<String> r = controller.callback(null, "admin-consent", "True", null, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("Microsoft 365 is connected");
        verifyNoInteractions(service);
    }

    @Test
    void microsoftError_isShownEscaped() {
        ResponseEntity<String> r = controller.callback(null, "s", null, "access_denied",
                "<script>alert(1)</script>\r\nTrace ID: 123");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).doesNotContain("<script>").contains("&lt;script&gt;").doesNotContain("Trace ID");
        verifyNoInteractions(service);
    }

    @Test
    void consentFailure_showsItsMessage() {
        when(service.complete(any(), any())).thenThrow(new ConsentException("This link has expired"));

        ResponseEntity<String> r = controller.callback("code", "old", null, null, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("This link has expired");
        verify(service).complete("code", "old");
    }

    @Test
    void consentLink_notConfigured_is409() {
        when(service.consentLink()).thenThrow(new IllegalStateException("not configured"));

        assertThat(controller.consentLink().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
