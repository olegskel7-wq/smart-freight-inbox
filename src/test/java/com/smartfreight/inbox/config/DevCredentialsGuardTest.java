package com.smartfreight.inbox.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevCredentialsGuardTest {

    private static final String STRONG = "s3cr3t-Value-0f-24-chars";

    /** Every secret set to its dev default — what a fresh clone resolves. */
    private static MockEnvironment devDefaults(String issuer) {
        return new MockEnvironment()
                .withProperty(DevCredentialsGuard.ISSUER_PROPERTY, issuer)
                .withProperty("spring.datasource.password", "smartfreight_app")
                .withProperty("spring.flyway.password", "postgres")
                .withProperty("spring.rabbitmq.password", "smartfreight")
                .withProperty("spring.data.redis.password", "smartfreight-dev-redis")
                .withProperty("keycloak.admin.client-secret", "smartfreight-admin-secret")
                .withProperty("port.service.api-key", "smartfreight-port-secret")
                .withProperty("mailbox.encryption-key", "");
    }

    private static MockEnvironment allStrong(String issuer) {
        MockEnvironment env = new MockEnvironment().withProperty(DevCredentialsGuard.ISSUER_PROPERTY, issuer);
        DevCredentialsGuard.DEV_DEFAULTS.keySet().forEach(name -> env.setProperty(name, STRONG));
        return env;
    }

    @Test
    void localIssuer_withDevDefaults_startsAndOnlyWarns() {
        assertThatCode(() -> new DevCredentialsGuard(devDefaults("https://localhost/realms/smartfreight")))
                .doesNotThrowAnyException();
        assertThatCode(() -> new DevCredentialsGuard(devDefaults("http://127.0.0.1:8081/realms/smartfreight")))
                .doesNotThrowAnyException();
    }

    @Test
    void publicIssuer_withDevDefaults_refusesToStart_namingPropertiesNotValues() {
        assertThatThrownBy(() -> new DevCredentialsGuard(devDefaults("https://app.smartfreight.co.uk/realms/smartfreight")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.smartfreight.co.uk")
                .hasMessageContaining("spring.data.redis.password")
                .hasMessageContaining("mailbox.encryption-key")
                .hasMessageNotContaining("smartfreight-dev-redis")
                .hasMessageNotContaining("smartfreight-admin-secret");
    }

    @Test
    void publicIssuer_withStrongSecrets_starts() {
        assertThatCode(() -> new DevCredentialsGuard(allStrong("https://app.smartfreight.co.uk/realms/smartfreight")))
                .doesNotThrowAnyException();
    }

    @Test
    void oneShortSecret_isEnoughToRefuseOnAPublicHost() {
        MockEnvironment env = allStrong("https://app.smartfreight.co.uk/realms/smartfreight");
        env.setProperty("spring.rabbitmq.password", "short-pass");

        assertThat(DevCredentialsGuard.weakSecrets(env)).containsExactly("spring.rabbitmq.password");
        assertThatThrownBy(() -> new DevCredentialsGuard(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bothPortServiceDevKeysCountAsDefaults() {
        MockEnvironment env = allStrong("https://localhost/realms/smartfreight");
        env.setProperty("port.service.api-key", "local-dev-api-key");

        assertThat(DevCredentialsGuard.weakSecrets(env)).containsExactly("port.service.api-key");
    }
}
