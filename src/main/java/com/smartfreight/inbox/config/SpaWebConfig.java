package com.smartfreight.inbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the React SPA bundle (Phase 3.2) and makes client-side deep links work.
 *
 * <p>The Vite build lands in {@code classpath:/static/app/} (base {@code /app/}). Real files
 * (assets, index.html) are served directly; any other {@code /app/**} path — e.g.
 * {@code /app/tickets/42} entered or refreshed in the browser — falls back to {@code index.html}
 * so React Router can resolve the route. The handler is scoped to {@code /app/**}, so it never
 * shadows the Thymeleaf routes or {@code /api}.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    private static final String LOCATION = "classpath:/static/app/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/app/**")
                .addResourceLocations(LOCATION)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // A real file under /app (assets, index.html) — serve it. A blank path is the
                        // SPA root (/app/, e.g. the OIDC callback) and must NOT resolve to the
                        // directory — fall through to the shell.
                        if (!resourcePath.isBlank()) {
                            Resource requested = location.createRelative(resourcePath);
                            if (requested.exists() && requested.isReadable()) {
                                return requested;
                            }
                        }
                        // Root or unknown path under /app — hand back the SPA shell for client-side routing.
                        Resource index = location.createRelative("index.html");
                        return (index.exists() && index.isReadable()) ? index : null;
                    }
                });
    }
}
