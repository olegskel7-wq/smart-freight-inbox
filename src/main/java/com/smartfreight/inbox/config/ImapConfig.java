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

// @Configuration — Spring загружает этот класс при запуске
@Configuration
public class ImapConfig {

    private static final Logger log = LoggerFactory.getLogger(ImapConfig.class);

    // Читаем настройки почты из application.yaml.
    // Синтаксис ${ПЕРЕМЕННАЯ:значение_по_умолчанию} — если переменная окружения не задана, берём заглушку.

    @Value("${spring.mail.host}")
    private String host;         // IMAP-сервер (например: imap.gmail.com)

    @Value("${spring.mail.port:993}")
    private int port;            // Порт IMAPS (993 = IMAP + SSL, стандартный)

    @Value("${spring.mail.username}")
    private String username;     // Адрес почтового ящика (inbox@company.com)

    @Value("${spring.mail.password}")
    private String password;     // Пароль или App Password (для Gmail)

    // MessageChannel — "труба", по которой Spring Integration передаёт сообщения между компонентами.
    // DirectChannel — синхронная доставка: отправитель ждёт, пока получатель завершит обработку.
    // Имя бина "mailChannel" указывается в @ServiceActivator(inputChannel = "mailChannel").
    @Bean
    public MessageChannel mailChannel() {
        return new DirectChannel();
    }

    // IntegrationFlow — описывает конвейер данных в Spring Integration (DSL-стиль, Spring Integration 7.x).
    // Поток: IMAP-сервер → адаптер получает письмо → отправляет в mailChannel → EmailIngestionService обрабатывает.
    @Bean
    public IntegrationFlow imapIdleFlow(MessageChannel mailChannel) {

        // Проверяем: настроены ли реальные учётные данные или всё ещё стоят заглушки?
        // Если host = "imap.example.com" (значение по умолчанию) — реальные данные не заданы.
        boolean configured = !host.equals("imap.example.com") && !password.equals("changeme");

        // Если данные не настроены — отключаем IMAP-адаптер.
        // Важно при разработке: не засорять логи бессмысленными ошибками подключения.
        if (!configured) {
            log.warn("IMAP not configured — adapter disabled. Set IMAP_HOST, IMAP_USER, IMAP_PASSWORD env vars to enable.");
            // Возвращаем пустой поток: mailChannel → nullChannel (письма никуда не идут)
            return IntegrationFlow.from(mailChannel).nullChannel();
        }

        // Собираем URL подключения IMAPS: imaps://пользователь:пароль@хост:порт/INBOX
        // Логин и пароль URL-кодируются: '@' в email-адресе иначе ломает парсинг URL
        String encodedUser = java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String encodedPassword = java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = String.format("imaps://%s:%s@%s:%d/INBOX", encodedUser, encodedPassword, host, port);

        // Строим конвейер данных через DSL (Domain Specific Language)
        return IntegrationFlow
                // Mail.imapIdleAdapter — IMAP IDLE адаптер (современный API Spring Integration 7.x).
                // IMAP IDLE: сервер сам присылает уведомление когда приходит новое письмо (push, не опрос).
                // В отличие от polling (проверять каждые N секунд) — реакция мгновенная.
                .from(Mail.imapIdleAdapter(url)
                        // Свойства JavaMail для IMAP-соединения
                        .javaMailProperties(p -> {
                            p.put("mail.store.protocol", "imaps");
                            // Явно передаём учётные данные — Jakarta Mail не извлекает их из imaps:// URL
                            p.put("mail.imaps.user", username);
                            p.put("mail.imaps.password", password);
                            p.put("mail.imaps.auth", "true");
                            p.put("mail.imaps.connectiontimeout", "10000");
                            p.put("mail.imaps.timeout", "60000");
                            p.put("mail.imaps.keepalive.interval", "60000");
                            p.put("mail.imaps.ssl.enable", "true");
                            // Доверяем только конкретному IMAP-хосту, не всем сертификатам
                            p.put("mail.imaps.ssl.trust", host);
                            // Отключаем частичную загрузку: без этого тело письма читается лениво
                            // и при переключении из IDLE-режима возникает FolderClosedException
                            p.put("mail.imaps.partialfetch", "false");
                            p.put("mail.imaps.fetchsize", "1048576");
                        })
                        // Помечаем письма как "Прочитанные" на сервере после получения
                        .shouldMarkMessagesAsRead(true)
                        // НЕ удаляем письма с сервера после чтения (безопасное поведение по умолчанию)
                        .shouldDeleteMessages(false)
                        // Запускаем адаптер автоматически при старте приложения
                        .autoStartup(true))

                // Передаём полученное письмо в mailChannel.
                // Оттуда его подхватит @ServiceActivator в EmailIngestionService.
                .channel(mailChannel)
                .get(); // Завершаем построение конвейера
    }
}
