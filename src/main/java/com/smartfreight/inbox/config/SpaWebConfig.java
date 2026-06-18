package com.smartfreight.inbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the React SPA bundle and makes client-side deep links work.
 *
 * <p>Since the Phase 3.2 cutover the SPA is the application UI and is served from the root.
 * The Vite build still lands in {@code classpath:/static/app/} (the build outDir is unchanged;
 * only the public base moved from {@code /app/} to {@code /}). Real files (assets, index.html,
 * favicon) are served directly; any other path — e.g. {@code /tickets} or {@code /tickets/42}
 * entered or refreshed in the browser — falls back to {@code index.html} so React Router can
 * resolve the route.
 *
 * <p>The handler is a {@code /**} catch-all, which is the LOWEST-priority handler mapping:
 * {@code @RestController}s ({@code /api/**}, {@code /api/admin/**}), the actuator endpoints and
 * the STOMP {@code /ws} endpoint are all higher-precedence handler mappings and win, so this
 * never shadows the API, the metrics or the WebSocket. Only genuinely unmapped paths reach here
 * and are handed the SPA shell.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    private static final String LOCATION = "classpath:/static/app/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(LOCATION)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // A real file in the bundle (assets, index.html, favicon) — serve it.
                        if (!resourcePath.isBlank()) {
                            Resource requested = location.createRelative(resourcePath);
                            if (requested.exists() && requested.isReadable()) {
                                return requested;
                            }
                        }
                        // Root or an unmapped client-side route — hand back the SPA shell so
                        // React Router can take over.
                        Resource index = location.createRelative("index.html");
                        return (index.exists() && index.isReadable()) ? index : null;
                    }
                });
    }
}
