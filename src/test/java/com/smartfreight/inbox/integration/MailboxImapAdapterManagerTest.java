package com.smartfreight.inbox.integration;

import com.smartfreight.inbox.service.SecretCipher;
import com.smartfreight.inbox.tenancy.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.integration.dsl.context.IntegrationFlowContext;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Unit test for the adapter → ingestion hop of {@link MailboxImapAdapterManager}: every
 * arriving message is ingested inside the mailbox's tenant AND attributed to the mailbox, so
 * the reply later leaves through the same inbox (V37).
 */
class MailboxImapAdapterManagerTest {

    @Test
    void ingest_bindsTheMailboxTenant_andPassesTheMailboxId() {
        EmailIngestionService ingestion = mock(EmailIngestionService.class);
        MailboxImapAdapterManager manager = new MailboxImapAdapterManager(
                mock(IntegrationFlowContext.class), ingestion, mock(SecretCipher.class), new SimpleMeterRegistry());
        MimeMessage message = mock(MimeMessage.class);
        AtomicReference<Long> tenantDuringIngestion = new AtomicReference<>();
        doAnswer(inv -> {
            tenantDuringIngestion.set(TenantContext.tenantIdOrNull());
            return null;
        }).when(ingestion).processIncomingEmail(any(), eq(7L));

        manager.ingest(90001L, 7L, message);

        assertThat(tenantDuringIngestion.get()).isEqualTo(90001L);
    }
}
