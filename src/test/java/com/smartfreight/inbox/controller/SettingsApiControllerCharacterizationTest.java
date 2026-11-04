package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.entity.TenantMailbox;
import com.smartfreight.inbox.integration.MailboxHealthMonitor;
import com.smartfreight.inbox.integration.MailboxImapAdapterManager;
import com.smartfreight.inbox.scheduler.QuoteFollowUpScheduler;
import com.smartfreight.inbox.scheduler.StatusWatchScheduler;
import com.smartfreight.inbox.service.AutoSendService;
import com.smartfreight.inbox.service.EmailClassificationService;
import com.smartfreight.inbox.service.MailboxManagementService;
import com.smartfreight.inbox.service.OllamaClientProvider;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.service.SettingsService;
import com.smartfreight.inbox.service.TicketProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Records what the {@code /api/settings} endpoints do today, ahead of splitting the controller: the
 * snapshot fields and the defaults it reads with, each save's stored values, every rejection's status
 * and exact message, and that a rejected save stores nothing. {@link SettingsApiControllerTest} keeps
 * the earlier, broader cases. Some of this is pinned as-is rather than as a wish, see the comments.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettingsApiControllerCharacterizationTest {

    private static final String MAILBOX_JSON = """
            {"host":"imap.x.com","port":993,"username":"u@x.com","password":"p",
             "smtpHost":"smtp.x.com","smtpPort":587,"fromAddress":"sales@x.com"}""";

    @Mock private SettingsService settingsService;
    @Mock private MailboxManagementService mailboxService;
    @Mock private MailboxImapAdapterManager adapterManager;
    @Mock private MailboxHealthMonitor mailboxHealth;
    @Mock private SecretCipher secretCipher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Every read answers with the default it was asked with, so the defaults show in the snapshot.
        when(settingsService.getString(anyString(), any())).thenAnswer(call -> call.getArgument(1));
        when(settingsService.isFeatureEnabled(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        when(settingsService.getTenantOverride(anyString())).thenReturn(Optional.empty());
        when(mailboxService.findAll()).thenReturn(List.of());

        SettingsSnapshotAssembler assembler = new SettingsSnapshotAssembler(
                settingsService, mailboxService, adapterManager, mailboxHealth, secretCipher);
        ReflectionTestUtils.setField(assembler, "smtpHost", "smtp.platform.test");
        ReflectionTestUtils.setField(assembler, "smtpPort", 2525);
        ReflectionTestUtils.setField(assembler, "smtpUser", "platform@test");
        ReflectionTestUtils.setField(assembler, "platformOllamaUrl", "http://ollama.platform:11434");
        mockMvc = MockMvcBuilders.standaloneSetup(
                new SettingsApiController(settingsService, assembler),
                new MailboxSettingsController(mailboxService, assembler)).build();
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType("application/json").content(json));
    }

    private ResultActions putJson(String path, String json) throws Exception {
        return mockMvc.perform(put(path).contentType("application/json").content(json));
    }

    private void verifyNothingStored() {
        verify(settingsService, never()).setString(anyString(), any());
    }

    // ------------------------------------------------------------------------- snapshot

    @Test
    void snapshot_readsEverySettingWithItsDefault() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cipherConfigured").value(false))
                .andExpect(jsonPath("$.ollamaUrlOverride").value(""))
                .andExpect(jsonPath("$.extractionPrompt").value(""))
                .andExpect(jsonPath("$.draftingPrompt").value(""))
                .andExpect(jsonPath("$.autoSendEnabled").value(false))
                .andExpect(jsonPath("$.autoSendThreshold").value("0.8"))
                .andExpect(jsonPath("$.autoSendTypes", contains("STATUS_UPDATE")))
                .andExpect(jsonPath("$.statusUpdateEnabled").value(false))
                .andExpect(jsonPath("$.statusUpdateMilestones").value(StatusWatchScheduler.DEFAULT_MILESTONES))
                .andExpect(jsonPath("$.quoteFollowUpEnabled").value(false))
                .andExpect(jsonPath("$.quoteFollowUpDelayHours")
                        .value(String.valueOf(QuoteFollowUpScheduler.DEFAULT_DELAY_HOURS)))
                .andExpect(jsonPath("$.smartRoutingEnabled").value(true))
                .andExpect(jsonPath("$.escalationAssignee").value(""))
                .andExpect(jsonPath("$.mailboxes").isEmpty());
    }

    @Test
    void snapshot_carriesThePlatformValuesFromConfiguration() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.smtpHost").value("smtp.platform.test"))
                .andExpect(jsonPath("$.smtpPort").value(2525))
                .andExpect(jsonPath("$.smtpUser").value("platform@test"))
                .andExpect(jsonPath("$.platformOllamaUrl").value("http://ollama.platform:11434"));
    }

    @Test
    void snapshot_readsTheOllamaOverrideOfTheTenantOnly() throws Exception {
        when(settingsService.getTenantOverride(OllamaClientProvider.KEY_OLLAMA_BASE_URL))
                .thenReturn(Optional.of("http://ollama.tenant:11434"));

        mockMvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.ollamaUrlOverride").value("http://ollama.tenant:11434"));
    }

    @Test
    void snapshot_splitsTheTypeListAndDropsBlanks() throws Exception {
        when(settingsService.getString(AutoSendService.KEY_TYPES, "STATUS_UPDATE")).thenReturn(" RFQ,, GENERAL , ");

        mockMvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.autoSendTypes", contains("RFQ", "GENERAL")));
    }

    @Test
    void snapshot_listsEveryTicketTypeInEnumOrder() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.allTicketTypes", contains(
                        "RFQ", "STATUS_UPDATE", "BOOKING_CONFIRM", "INVOICE_QUERY", "DOCUMENT_REQUEST", "GENERAL")));
    }

    @Test
    void snapshot_showsAMailboxWithoutHealthDataOrSmtpAsUnknown() throws Exception {
        TenantMailbox inboundOnly = new TenantMailbox();
        inboundOnly.setId(5L);
        inboundOnly.setHost("imap.acme.test");
        inboundOnly.setPort(993);
        inboundOnly.setUsername("ops@acme.test");
        when(mailboxService.findAll()).thenReturn(List.of(inboundOnly));
        when(adapterManager.isRunning(5L)).thenReturn(false);
        when(mailboxHealth.health(5L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.mailboxes[0].running").value(false))
                .andExpect(jsonPath("$.mailboxes[0].loginOk").value(nullValue()))
                .andExpect(jsonPath("$.mailboxes[0].lastLoginOkAt").value(nullValue()))
                .andExpect(jsonPath("$.mailboxes[0].loginError").value(nullValue()))
                .andExpect(jsonPath("$.mailboxes[0].sendsAs").value(nullValue()))
                .andExpect(jsonPath("$.mailboxes[0].smtpHost").value(nullValue()));
    }

    // ------------------------------------------------------------------------- auto-send

    @ParameterizedTest
    @CsvSource({"' 0.7 ',0.7", "-3,0.0", "7,1.0", "0,0.0", "1,1.0"})
    void autoSend_trimsAndClampsTheThreshold(String sent, String stored) throws Exception {
        postJson("/api/settings/autosend", "{\"enabled\":false,\"threshold\":\"" + sent + "\",\"types\":[]}")
                .andExpect(status().isOk());

        verify(settingsService).setString(AutoSendService.KEY_THRESHOLD, stored);
    }

    /** Pinned as-is: {@code NaN} parses as a double and survives the clamp. See docs/refactoring.md. */
    @Test
    void autoSend_storesNaNAsTheThreshold() throws Exception {
        postJson("/api/settings/autosend", "{\"enabled\":true,\"threshold\":\"NaN\",\"types\":[]}")
                .andExpect(status().isOk());

        verify(settingsService).setString(AutoSendService.KEY_THRESHOLD, "NaN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"enabled\":true,\"types\":[]}", "{\"enabled\":true,\"threshold\":\"high\"}"})
    void autoSend_rejectsAMissingOrMalformedThreshold_storingNothing(String json) throws Exception {
        postJson("/api/settings/autosend", json)
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("Threshold must be a number between 0 and 1."));

        verifyNothingStored();
    }

    @Test
    void autoSend_normalisesTypes_keepingTheirOrder_andDroppingUnknownOnes() throws Exception {
        postJson("/api/settings/autosend",
                "{\"enabled\":false,\"threshold\":\"0.5\",\"types\":[\" general \",\"nonsense\",\"Rfq\",\"\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailboxes").isArray());

        verify(settingsService).setString(AutoSendService.FEATURE_KEY, "false");
        verify(settingsService).setString(AutoSendService.KEY_TYPES, "GENERAL,RFQ");
    }

    @Test
    void autoSend_withoutATypeList_storesNoTypes_evenWhenEnabled() throws Exception {
        postJson("/api/settings/autosend", "{\"enabled\":true,\"threshold\":\"0.5\"}")
                .andExpect(status().isOk());

        verify(settingsService).setString(AutoSendService.FEATURE_KEY, "true");
        verify(settingsService).setString(AutoSendService.KEY_TYPES, "");
    }

    // --------------------------------------------------------------------- status update

    /** Pinned as-is: milestones are trimmed as a whole, their case and inner spaces are kept. */
    @Test
    void statusUpdate_storesTheMilestonesTrimmedAsAWhole() throws Exception {
        postJson("/api/settings/statusupdate", "{\"enabled\":true,\"milestones\":\"  at_port, RELEASED \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailboxes").isArray());

        verify(settingsService).setString(StatusWatchScheduler.FEATURE_KEY, "true");
        verify(settingsService).setString(StatusWatchScheduler.KEY_MILESTONES, "at_port, RELEASED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"enabled\":false}", "{\"enabled\":false,\"milestones\":\"   \"}"})
    void statusUpdate_disabled_acceptsNoMilestones(String json) throws Exception {
        postJson("/api/settings/statusupdate", json).andExpect(status().isOk());

        verify(settingsService).setString(StatusWatchScheduler.FEATURE_KEY, "false");
        verify(settingsService).setString(StatusWatchScheduler.KEY_MILESTONES, "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"enabled\":true}", "{\"enabled\":true,\"milestones\":\"   \"}"})
    void statusUpdate_enabled_rejectsNoMilestones_storingNothing(String json) throws Exception {
        postJson("/api/settings/statusupdate", json)
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(
                        "Status-update automation is on but no milestones are set — nothing would be sent."));

        verifyNothingStored();
    }

    // ------------------------------------------------------------------- quote follow-up

    @ParameterizedTest
    @CsvSource({"' 24 ',24", "0,0", "007,7"})
    void quoteFollowUp_storesTheParsedDelay(String sent, String stored) throws Exception {
        postJson("/api/settings/quotefollowup", "{\"enabled\":true,\"delayHours\":\"" + sent + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailboxes").isArray());

        verify(settingsService).setString(QuoteFollowUpScheduler.FEATURE_KEY, "true");
        verify(settingsService).setString(QuoteFollowUpScheduler.KEY_DELAY_HOURS, stored);
    }

    @Test
    void quoteFollowUp_disabled_storesTheFlagOff() throws Exception {
        postJson("/api/settings/quotefollowup", "{\"enabled\":false,\"delayHours\":\"48\"}")
                .andExpect(status().isOk());

        verify(settingsService).setString(QuoteFollowUpScheduler.FEATURE_KEY, "false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"enabled\":true}", "{\"enabled\":true,\"delayHours\":\"soon\"}",
            "{\"enabled\":true,\"delayHours\":\"1.5\"}"})
    void quoteFollowUp_rejectsAMissingOrMalformedDelay_storingNothing(String json) throws Exception {
        postJson("/api/settings/quotefollowup", json)
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("Delay must be a whole number of hours."));

        verifyNothingStored();
    }

    @Test
    void quoteFollowUp_rejectsANegativeDelay_storingNothing() throws Exception {
        postJson("/api/settings/quotefollowup", "{\"enabled\":true,\"delayHours\":\"-1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("Delay cannot be negative."));

        verifyNothingStored();
    }

    // --------------------------------------------------------------------------- routing

    @Test
    void routing_withoutAnAssignee_clearsIt() throws Exception {
        postJson("/api/settings/routing", "{\"smartRoutingEnabled\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailboxes").isArray());

        verify(settingsService).setString(EmailClassificationService.FEATURE_SMART_ROUTING, "false");
        verify(settingsService).setString(TicketProcessingService.SETTING_ESCALATION_ASSIGNEE, "");
    }

    // ---------------------------------------------------------------------------- ollama

    @ParameterizedTest
    @CsvSource({"' http://ollama:11434 ',http://ollama:11434", "https://ollama.test,https://ollama.test"})
    void ollama_storesAnHttpUrlTrimmed(String sent, String stored) throws Exception {
        postJson("/api/settings/ollama", "{\"ollamaBaseUrl\":\"" + sent + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailboxes").isArray());

        verify(settingsService).setString(OllamaClientProvider.KEY_OLLAMA_BASE_URL, stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"ollamaBaseUrl\":\"   \"}"})
    void ollama_blankOrMissing_clearsTheOverride(String json) throws Exception {
        postJson("/api/settings/ollama", json).andExpect(status().isOk());

        verify(settingsService).setString(OllamaClientProvider.KEY_OLLAMA_BASE_URL, "");
    }

    @Test
    void ollama_rejectsAnotherScheme_storingNothing() throws Exception {
        postJson("/api/settings/ollama", "{\"ollamaBaseUrl\":\"ftp://ollama\"}")
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("Ollama URL must start with http:// or https://."));

        verifyNothingStored();
    }

    // ------------------------------------------------------------ notifications, prompts

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void notifications_storeTheFlag_andAnswerWithTheSnapshot(boolean enabled) throws Exception {
        postJson("/api/settings/notifications", "{\"draftEnabled\":" + enabled + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailboxes").isArray());

        verify(settingsService).setDraftEnabled(enabled);
    }

    @Test
    void prompts_storeBothTemplatesVerbatim_andAnswerWithTheSnapshot() throws Exception {
        postJson("/api/settings/prompts", "{\"extractionPrompt\":\" EX {email} \",\"draftingPrompt\":\"DR\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailboxes").isArray());

        verify(settingsService).setString(SettingsService.KEY_EXTRACTION_PROMPT, " EX {email} ");
        verify(settingsService).setString(SettingsService.KEY_DRAFTING_PROMPT, "DR");
    }

    // ------------------------------------------------------------------------- mailboxes

    @Test
    void addMailbox_passesEveryField_andAnswersWithTheSnapshot() throws Exception {
        postJson("/api/settings/mailboxes", MAILBOX_JSON)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailboxes").isArray());

        verify(mailboxService).create("imap.x.com", 993, "u@x.com", "p", "smtp.x.com", 587, "sales@x.com");
    }

    @Test
    void addMailbox_explainsADuplicate() throws Exception {
        doThrow(new DataIntegrityViolationException("uq")).when(mailboxService)
                .create(any(), any(), any(), any(), any(), any(), any());

        postJson("/api/settings/mailboxes", MAILBOX_JSON)
                .andExpect(status().isConflict())
                .andExpect(status().reason("This mailbox (host + username) is already configured."));
    }

    @Test
    void addMailbox_passesOnTheValidationMessage() throws Exception {
        doThrow(new IllegalArgumentException("Host is required")).when(mailboxService)
                .create(any(), any(), any(), any(), any(), any(), any());

        postJson("/api/settings/mailboxes", MAILBOX_JSON)
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("Host is required"));
    }

    @Test
    void addMailbox_passesOnTheStateMessage() throws Exception {
        doThrow(new IllegalStateException("No encryption key")).when(mailboxService)
                .create(any(), any(), any(), any(), any(), any(), any());

        postJson("/api/settings/mailboxes", MAILBOX_JSON)
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("No encryption key"));
    }

    @Test
    void updateMailbox_explainsADuplicate() throws Exception {
        doThrow(new DataIntegrityViolationException("uq")).when(mailboxService)
                .update(any(), any(), any(), any(), any(), any(), any(), any());

        putJson("/api/settings/mailboxes/7", MAILBOX_JSON)
                .andExpect(status().isConflict())
                .andExpect(status().reason("Another mailbox with this host + username is already configured."));
    }

    @Test
    void updateMailbox_passesOnTheMessageOfAnUnknownMailbox() throws Exception {
        RuntimeException notFound = new MailboxManagementService.MailboxNotFoundException(99L);
        doThrow(notFound).when(mailboxService).update(any(), any(), any(), any(), any(), any(), any(), any());

        putJson("/api/settings/mailboxes/99", MAILBOX_JSON)
                .andExpect(status().isNotFound())
                .andExpect(status().reason(notFound.getMessage()));
    }

    @Test
    void updateMailbox_passesOnTheValidationMessage() throws Exception {
        doThrow(new IllegalArgumentException("Password required")).when(mailboxService)
                .update(any(), any(), any(), any(), any(), any(), any(), any());

        putJson("/api/settings/mailboxes/7", MAILBOX_JSON)
                .andExpect(status().isBadRequest())
                .andExpect(status().reason("Password required"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void toggleMailbox_setsTheFlag_andAnswersWithTheSnapshot(boolean enabled) throws Exception {
        postJson("/api/settings/mailboxes/7/toggle", "{\"enabled\":" + enabled + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailboxes").isArray());

        verify(mailboxService).setEnabled(7L, enabled);
    }

    @Test
    void toggleMailbox_unknown_isNotFound() throws Exception {
        RuntimeException notFound = new MailboxManagementService.MailboxNotFoundException(99L);
        doThrow(notFound).when(mailboxService).setEnabled(99L, true);

        postJson("/api/settings/mailboxes/99/toggle", "{\"enabled\":true}")
                .andExpect(status().isNotFound())
                .andExpect(status().reason(notFound.getMessage()));
    }

    @Test
    void deleteMailbox_answersNoContent_withoutASnapshot() throws Exception {
        mockMvc.perform(delete("/api/settings/mailboxes/7"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(mailboxService).delete(7L);
    }
}
