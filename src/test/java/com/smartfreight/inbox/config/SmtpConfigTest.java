package com.smartfreight.inbox.config;

import com.smartfreight.inbox.service.MailTlsPolicy;
import com.smartfreight.inbox.startup.DemoMailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link SmtpConfig} — the platform SMTP account gets the same verified-TLS
 * profile as a tenant's: STARTTLS required on 587, certificate + hostname checked, no ssl.trust.
 */
class SmtpConfigTest {

    @Test
    void platformSender_requiresStartTlsAndVerifiesTheCertificate() {
        JavaMailSenderImpl sender = (JavaMailSenderImpl) new SmtpConfig().javaMailSender(
                "smtp.gmail.com", 587, "platform@smartfreight.com", "pw",
                new MailTlsPolicy(new DemoMailProperties()));

        Properties props = sender.getJavaMailProperties();
        assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(props.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(props).doesNotContainKey("mail.smtp.ssl.trust");
    }
}
