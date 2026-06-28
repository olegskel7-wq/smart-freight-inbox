package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.KnowledgeDocDto;
import com.smartfreight.inbox.controller.dto.KnowledgeResponse;
import com.smartfreight.inbox.service.KnowledgeBaseService;
import com.smartfreight.inbox.service.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * JSON API backing the React {@code /knowledge} page (Phase 3.9 — Knowledge-base deflection).
 * Mirrors the content-page pattern of {@code /rates} / {@code /documents}: snapshot DTOs over
 * {@code /api/knowledge}, secured by the stateless resource-server chain (bearer JWT).
 *
 * <p>OPERATOR-gated: it sits under {@code anyRequest().authenticated()} (only {@code /admin/tenants}
 * is SUPER_ADMIN, {@code /users}/{@code /settings}/{@code /webhooks} ADMIN), so {@code SecurityConfig}
 * is not touched. Documents are tenant-scoped by {@code @TenantId} + RLS, so the page only ever shows
 * (and mutates) the current tenant's knowledge. Every mutation returns the refreshed snapshot so the
 * SPA re-renders without a second round trip.
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeApiController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final SettingsService settingsService;

    public KnowledgeApiController(KnowledgeBaseService knowledgeBaseService,
                                  SettingsService settingsService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.settingsService = settingsService;
    }

    /** Body for create/update — title + content (both required, validated in the service). */
    public record KnowledgeRequest(String title, String content) {
    }

    /** GET /api/knowledge — the current tenant's knowledge documents plus the feature-flag state. */
    @GetMapping
    public KnowledgeResponse get() {
        return buildResponse();
    }

    /** POST /api/knowledge — stores a new document (best-effort embedded). Blank field → 400. */
    @PostMapping
    public KnowledgeResponse create(@RequestBody KnowledgeRequest request) {
        try {
            knowledgeBaseService.create(request.title(), request.content());
            return buildResponse();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** PUT /api/knowledge/{id} — updates a document (re-embedded). Unknown id / blank field → 400. */
    @PutMapping("/{id}")
    public KnowledgeResponse update(@PathVariable Long id, @RequestBody KnowledgeRequest request) {
        try {
            knowledgeBaseService.update(id, request.title(), request.content());
            return buildResponse();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** DELETE /api/knowledge/{id} — removes one document. RLS scopes the delete to the tenant. */
    @DeleteMapping("/{id}")
    public KnowledgeResponse delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return buildResponse();
    }

    /** Snapshots the current tenant's knowledge documents (newest first) into the DTO. */
    private KnowledgeResponse buildResponse() {
        List<KnowledgeDocDto> documents = knowledgeBaseService.list()
                .stream()
                .map(KnowledgeDocDto::from)
                .toList();
        boolean featureEnabled = settingsService.isFeatureEnabled(KnowledgeBaseService.FEATURE_KEY, true);
        return new KnowledgeResponse(featureEnabled, documents);
    }
}
