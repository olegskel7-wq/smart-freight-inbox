package com.smartfreight.inbox.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mail.dsl.Mail;
import org.springframework.messaging.MessageChannel;

/**
 * IMAP IDLE gaunamojo pašto konfigūracija.
 *
 * Sukuria Spring Integration konvejerį:
 * IMAP serveris → adapteris gauna laišką → siunčia į mailChannel → EmailIngestionService apdoroja.
 *
 * Jei konfigūracijos duomenys nenurodyti (host = imap.example.com) –
 * adapteris išjungiamas, kad nebūtų beprasmiškų jungimosi klaidų kūrimo aplinkoje.
 */
@Configuration
public class ImapConfig {

    private static final Logger log = LoggerFactory.getLogger(ImapConfig.class);

    @Value("${spring.mail.host}")
    private String host;

    @Value("${spring.mail.port:993}")
    private int port;

    @Value("${spring.mail.username}")
    private String username;

    @Value("${spring.mail.password}")
    private String password;

    /**
     * Sinchroninis pranešimų kanalas gaunamiems laiškams.
     *
     * DirectChannel – sinchroninis pristatymas: siuntėjas laukia, kol gavėjas baigia apdorojimą.
     * Bean pavadinimas "mailChannel" nurodomas @ServiceActivator(inputChannel = "mailChannel").
     *
     * @return sukonfigūruotas DirectChannel
     */
    @Bean
    public MessageChannel mailChannel() {
        return new DirectChannel();
    }

    /**
     * IMAP IDLE konvejeris – pristato naujus laiškus į mailChannel.
     *
     * IMAP IDLE: serveris pats praneša apie naujus laiškus (push protokolas, ne apklausa).
     * Tai leidžia reaguoti akimirksniu, skirtingai nuo polling (tikrinimas kas N sekundžių).
     *
     * Jei konfigūracijos duomenys nenurodyti (host = "imap.example.com" arba password = "changeme") –
     * grąžinamas tuščias srautas: mailChannel → nullChannel.
     * Tai svarbu kūrimo aplinkoje, kad neatsirastu jungimosi klaidų.
     *
     * @param mailChannel kanalas, į kurį siunčiami gauti laiškai
     * @return sukonfigūruotas IntegrationFlow
     */
    @Bean
    public IntegrationFlow imapIdleFlow(MessageChannel mailChannel) {
        boolean configured = !host.equals("imap.example.com") && !password.equals("changeme");

        if (!configured) {
            log.warn("IMAP not configured — adapter disabled. Set IMAP_HOST, IMAP_USER, IMAP_PASSWORD env vars to enable.");
            return IntegrationFlow.from(mailChannel).nullChannel();
        }

        // URL formatavimas: imaps://naudotojas:slaptažodis@host:port/INBOX
        // URL koduojamas: '@' el. pašte kitaip sugadintų URL analizę
        String encodedUser = java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String encodedPassword = java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = String.format("imaps://%s:%s@%s:%d/INBOX", encodedUser, encodedPassword, host, port);

        return IntegrationFlow
                .from(Mail.imapIdleAdapter(url)
                        .javaMailProperties(p -> {
                            p.put("mail.store.protocol", "imaps");
                            // Ryšio duomenys perduodami aiškiai – Jakarta Mail neistraukia jų iš imaps:// URL
                            p.put("mail.imaps.user", username);
                            p.put("mail.imaps.password", password);
                            p.put("mail.imaps.auth", "true");
                            p.put("mail.imaps.connectiontimeout", "10000");
                            p.put("mail.imaps.timeout", "60000");
                            p.put("mail.imaps.keepalive.interval", "60000");
                            p.put("mail.imaps.ssl.enable", "true");
                            // Pasitikime tik konkretaus IMAP serverio sertifikatu
                            p.put("mail.imaps.ssl.trust", host);
                            // Išjungiame dalinį įkėlimą: be to laiško turinys skaitomas tingiai
                            // ir persijungus iš IDLE režimo gaunama FolderClosedException
                            p.put("mail.imaps.partialfetch", "false");
                            p.put("mail.imaps.fetchsize", "1048576");
                        })
                        // Žymime laiškus kaip "Perskaityti" serveryje po gavimo
                        .shouldMarkMessagesAsRead(true)
                        // NЕТRINAME laiškų iš serverio (saugus numatytasis elgesys)
                        .shouldDeleteMessages(false)
                        .autoStartup(true))
                .channel(mailChannel)
                .get();
    }
}
