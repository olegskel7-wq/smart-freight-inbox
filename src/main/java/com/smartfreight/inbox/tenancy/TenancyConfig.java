package com.smartfreight.inbox.tenancy;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wires the two tenant-isolation layers (Phase 2.2):
 *
 * <ol>
 *   <li><b>Hibernate discriminator multi-tenancy</b> — {@link TenantIdentifierResolver}
 *       registered via {@code hibernate.tenant_identifier_resolver}; together with the
 *       {@code @TenantId} fields it filters entity queries and stamps inserts.</li>
 *   <li><b>Postgres RLS</b> — {@link TenantAwareDataSource} wraps the application pool so
 *       every connection checkout publishes the tenant into the {@code app.*} GUCs that
 *       the V19 policies read.</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(TenancyProperties.class)
public class TenancyConfig {

    /**
     * A bean (not an inline instance): the resolver listens for ApplicationReadyEvent to
     * switch from lenient bootstrap mode to strict fail-loud mode, and @EventListener
     * only works on container-managed beans.
     */
    @Bean
    public TenantIdentifierResolver tenantIdentifierResolver() {
        return new TenantIdentifierResolver();
    }

    @Bean
    public HibernatePropertiesCustomizer tenantIdentifierCustomizer(TenantIdentifierResolver resolver) {
        return hibernateProperties -> hibernateProperties.put(
                MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }

    /**
     * Wraps the auto-configured (Hikari) DataSource bean. A BeanPostProcessor — not a
     * replacement @Bean — keeps Boot's {@code spring.datasource.*} binding, health checks
     * and pool metrics intact. Flyway builds its own admin-role connection from
     * {@code spring.flyway.*} (not a bean), so migrations stay outside RLS by design.
     *
     * <p>Static: BPPs are created before almost everything else in the context.
     */
    @Bean
    public static BeanPostProcessor tenantAwareDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
