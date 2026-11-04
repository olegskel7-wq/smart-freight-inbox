package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.integration.MailboxHealthMonitor;
import com.smartfreight.inbox.integration.MailboxImapAdapterManager;
import com.smartfreight.inbox.service.MailboxManagementService;
import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc over the two settings controllers and the real snapshot assembler, with the
 * services mocked. No security or tenant filter here. The {@code @Value} platform fields of the
 * assembler stay unset unless a test sets them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class SettingsApiMockMvcSupport {

    @Mock SettingsService settingsService;
    @Mock MailboxManagementService mailboxService;
    @Mock MailboxImapAdapterManager adapterManager;
    @Mock MailboxHealthMonitor mailboxHealth;
    @Mock SecretCipher secretCipher;

    SettingsSnapshotAssembler assembler;
    MockMvc mockMvc;

    @BeforeEach
    void wireControllers() {
        assembler = new SettingsSnapshotAssembler(
                settingsService, mailboxService, adapterManager, mailboxHealth, secretCipher);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new SettingsApiController(settingsService, assembler),
                new MailboxSettingsController(mailboxService, assembler)).build();
    }
}
