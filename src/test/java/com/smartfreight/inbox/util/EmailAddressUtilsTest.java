package com.smartfreight.inbox.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link EmailAddressUtils#maskAddresses} — PII-safe mail error logging (Gate B3). */
class EmailAddressUtilsTest {

    @Test
    void maskAddresses_hidesLocalPartOfEveryAddress_keepsTheRest() {
        String smtpError = "550 5.1.1 <sue.clark+rfq@acmeparts.co.uk>: Recipient address rejected; "
                + "cc o'brien@mail.example.com";

        assertThat(EmailAddressUtils.maskAddresses(smtpError)).isEqualTo(
                "550 5.1.1 <***@acmeparts.co.uk>: Recipient address rejected; cc ***@mail.example.com");
    }

    @Test
    void maskAddresses_noAddressOrNull_unchanged() {
        assertThat(EmailAddressUtils.maskAddresses("Connection refused: smtp.gmail.com:587"))
                .isEqualTo("Connection refused: smtp.gmail.com:587");
        assertThat(EmailAddressUtils.maskAddresses(null)).isNull();
    }
}
