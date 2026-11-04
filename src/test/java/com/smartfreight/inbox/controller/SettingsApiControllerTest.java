package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.integration.MailboxHealthMonitor;
import com.smartfreight.inbox.scheduler.QuoteFollowUpScheduler;
import com.smartfreight.inbox.scheduler.StatusWatchScheduler;
import com.smartfreight.inbox.service.AutoSendService;
import com.smartfreight.inbox.service.EmailClassificationService;
import com.smartfreight.inbox.service.MailboxManagementService;
import com.smartfreight.inbox.service.OllamaClientProvider;
import com.smartfreight.inbox.service.SettingsService;
import com.smartfreight.inbox.service.TicketProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Snapshot and mailbox-row cases of the JSON settings API over a stubbed snapshot. Exact messages and
 * stored values are in {@link SettingsApiControllerCharacterizationTest}.
 */
class SettingsApiControllerTest extends SettingsApiMockMvcSupport {

    @BeforeEach
    void setUp() {
        // Snapshot stubs — the assembler reads these on every GET and on each successful save.
        when(secretCipher.isConfigured()).thenReturn(true);
        when(settingsService.isDraftEnabled()).thenReturn(true);
        when(settingsService.getTenantOverride(OllamaClientProvider.KEY_OLLAMA_BASE_URL))
                .thenReturn(Optional.empty());
        when(settingsService.getString(eq(SettingsService.KEY_EXTRACTION_PROMPT), anyString())).thenReturn("EX");
        when(settingsService.getString(eq(SettingsService.KEY_DRAFTING_PROMPT), anyString())).thenReturn("DR");
        when(settingsService.getString(eq(AutoSendService.KEY_THRESHOLD), anyString())).thenReturn("0.8");
        when(settingsService.getString(eq(AutoSendService.KEY_TYPES), anyString()))
                .thenReturn("STATUS_UPDATE, RFQ");
        when(settingsService.getString(eq(StatusWatchScheduler.KEY_MILESTONES), anyString()))
                .thenReturn("AT_PORT,RELEASED,DELIVERED");
        when(settingsService.getString(eq(QuoteFollowUpScheduler.KEY_DELAY_HOURS), anyString())).thenReturn("48");
        when(settingsService.isFeatureEnabled(eq(AutoSendService.FEATURE_KEY), anyBoolean())).thenReturn(false);
        when(settingsService.isFeatureEnabled(eq(StatusWatchScheduler.FEATURE_KEY), anyBoolean())).thenReturn(false);
        when(settingsService.isFeatureEnabled(eq(QuoteFollowUpScheduler.FEATURE_KEY), anyBoolean())).thenReturn(false);

        TenantMailbox m = new TenantMailbox();
        m.setId(7L);
        m.setHost("imap.acme.co.uk");
        m.setPort(993);
        m.setUsername("ops@acme.co.uk");
        m.setSmtpHost("smtp.acme.co.uk");
        m.setFromAddress("sales@acme.co.uk");
        m.setEnabled(true);
        when(mailboxService.findAll()).thenReturn(List.of(m));
        when(adapterManager.isRunning(7L)).thenReturn(true);
        when(mailboxHealth.health(7L)).thenReturn(Optional.of(new MailboxHealthMonitor.MailboxHealth(
                1L, false, Instant.now(), null, "AUTHENTICATIONFAILED Invalid credentials")));
    }

    @Test
    void getReturnsSnapshotWithSplitTypesAndMailbox() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cipherConfigured").value(true))
                .andExpect(jsonPath("$.draftEnabled").value(true))
                .andExpect(jsonPath("$.autoSendTypes[0]").value("STATUS_UPDATE"))
                .andExpect(jsonPath("$.autoSendTypes[1]").value("RFQ"))
                .andExpect(jsonPath("$.allTicketTypes").isArray())
                .andExpect(jsonPath("$.statusUpdateMilestones").value("AT_PORT,RELEASED,DELIVERED"))
                .andExpect(jsonPath("$.mailboxes[0].id").value(7))
                .andExpect(jsonPath("$.mailboxes[0].sendsAs").value("sales@acme.co.uk"))
                .andExpect(jsonPath("$.mailboxes[0].running").value(true))
                // Gate B4: registered is not connected — the login probe verdict is shown separately.
                .andExpect(jsonPath("$.mailboxes[0].loginOk").value(false))
                .andExpect(jsonPath("$.mailboxes[0].loginError").value("AUTHENTICATIONFAILED Invalid credentials"));
    }

    @Test
    void quoteFollowUpRejectsNegativeDelay() throws Exception {
        mockMvc.perform(post("/api/settings/quotefollowup")
                        .contentType("application/json")
                        .content("{\"enabled\":false,\"delayHours\":\"-3\"}"))
                .andExpect(status().isBadRequest());

        verify(settingsService, never()).setString(eq(QuoteFollowUpScheduler.KEY_DELAY_HOURS), anyString());
    }

    @Test
    void routingSavesFlagAndTrimmedAssignee() throws Exception {
        mockMvc.perform(post("/api/settings/routing")
                        .contentType("application/json")
                        .content("{\"smartRoutingEnabled\":true,\"escalationAssignee\":\"  uuid-123  \"}"))
                .andExpect(status().isOk());

        verify(settingsService).setString(EmailClassificationService.FEATURE_SMART_ROUTING, "true");
        verify(settingsService).setString(TicketProcessingService.SETTING_ESCALATION_ASSIGNEE, "uuid-123");
    }

    @Test
    void getExposesSmtpFieldsForTheEditFormButNeverThePassword() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.mailboxes[0].smtpHost").value("smtp.acme.co.uk"))
                .andExpect(jsonPath("$.mailboxes[0].fromAddress").value("sales@acme.co.uk"))
                .andExpect(jsonPath("$.mailboxes[0].password").doesNotExist())
                .andExpect(jsonPath("$.mailboxes[0].passwordEnc").doesNotExist());
    }

    @Test
    void updateMailboxEditsInPlaceAndReturnsSnapshot() throws Exception {
        mockMvc.perform(put("/api/settings/mailboxes/7")
                        .contentType("application/json")
                        .content("{\"host\":\"imap.acme.co.uk\",\"port\":993,\"username\":\"ops@acme.co.uk\","
                                + "\"password\":\"\",\"smtpHost\":\"smtp.acme.co.uk\",\"smtpPort\":465,"
                                + "\"fromAddress\":\"sales@acme.co.uk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailboxes[0].id").value(7));

        verify(mailboxService).update(7L, "imap.acme.co.uk", 993, "ops@acme.co.uk", "",
                "smtp.acme.co.uk", 465, "sales@acme.co.uk");
    }


    /** The update failures without a fixed message of their own in the characterisation test. */
    static Stream<Arguments> updateFailures() {
        return Stream.of(
                                Arguments.of(new IllegalStateException("MAILBOX_ENCRYPTION_KEY is not set"), 400),
                Arguments.of(new MailboxManagementService.MailboxInUseException(), 409));
    }

    @ParameterizedTest
    @MethodSource("updateFailures")
    void updateMapsEachFailureToItsStatus(RuntimeException failure, int expectedStatus) throws Exception {
        doThrow(failure).when(mailboxService).update(anyLong(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(put("/api/settings/mailboxes/7")
                        .contentType("application/json")
                        .content("{\"host\":\"imap.x.com\",\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void updateRenamingAMailboxInUseExplainsWhatToDoInstead() throws Exception {
        doThrow(new MailboxManagementService.MailboxInUseException())
                .when(mailboxService).update(anyLong(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(put("/api/settings/mailboxes/7")
                        .contentType("application/json")
                        .content("{\"host\":\"imap.x.com\",\"username\":\"sales@other.co.uk\",\"password\":\"p\"}"))
                .andExpect(status().reason(org.hamcrest.Matchers.containsString("separate mailbox")));
    }

    @Test
    void deleteUnknownMailboxIsNotFound() throws Exception {
        doThrow(new MailboxManagementService.MailboxNotFoundException(99L))
                .when(mailboxService).delete(anyLong());

        mockMvc.perform(delete("/api/settings/mailboxes/99"))
                .andExpect(status().isNotFound());
    }
}
