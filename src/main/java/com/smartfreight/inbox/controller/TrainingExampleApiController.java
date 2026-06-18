package com.smartfreight.inbox.controller;

import com.smartfreight.inbox.controller.dto.TrainingExampleDto;
import com.smartfreight.inbox.controller.dto.TrainingExamplesResponse;
import com.smartfreight.inbox.repository.TrainingExampleRepository;
import com.smartfreight.inbox.service.FeedbackLoopService;
import com.smartfreight.inbox.service.SettingsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * JSON API backing the React {@code /training-examples} page (Phase 3.2). It mirrors
 * {@link TrainingExampleController} (the Thymeleaf curation UI) but speaks DTOs over
 * {@code /api/training-examples} and is secured by the stateless resource-server chain (bearer JWT).
 * The two controllers run side by side during the migration; this one collapses to the only one when
 * Thymeleaf is removed.
 *
 * <p>The examples are tenant-scoped by {@code @TenantId} + RLS, so the page only ever shows the
 * current tenant's corrections. Delete returns the refreshed snapshot so the SPA re-renders without
 * a second round trip.
 */
@RestController
@RequestMapping("/api/training-examples")
public class TrainingExampleApiController {

    private final TrainingExampleRepository repository;
    private final SettingsService settingsService;

    public TrainingExampleApiController(TrainingExampleRepository repository,
                                        SettingsService settingsService) {
        this.repository = repository;
        this.settingsService = settingsService;
    }

    /** GET /api/training-examples — the current tenant's captured corrections (newest first). */
    @GetMapping
    public TrainingExamplesResponse get() {
        return buildResponse();
    }

    /**
     * DELETE /api/training-examples/{id} — removes one example from the training set (dataset
     * curation). RLS scopes the delete to the current tenant. Returns the refreshed snapshot.
     */
    @DeleteMapping("/{id}")
    public TrainingExamplesResponse delete(@PathVariable Long id) {
        repository.deleteById(id);
        return buildResponse();
    }

    /** Snapshots the current tenant's captured corrections (newest first) into the DTO. */
    private TrainingExamplesResponse buildResponse() {
        List<TrainingExampleDto> examples = repository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(TrainingExampleDto::from)
                .toList();
        boolean featureEnabled = settingsService.isFeatureEnabled(FeedbackLoopService.FEATURE_KEY, true);
        return new TrainingExamplesResponse(featureEnabled, examples);
    }
}
