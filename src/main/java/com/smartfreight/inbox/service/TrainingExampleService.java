package com.smartfreight.inbox.service;

import com.smartfreight.inbox.entity.TrainingExample;
import com.smartfreight.inbox.repository.TrainingExampleRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Curation of the captured corrections on {@code /training-examples}: list and delete. Capture and
 * retrieval live in {@link FeedbackLoopService}. Tenant-scoped by {@code @TenantId} + RLS.
 */
@Service
public class TrainingExampleService {

    private final TrainingExampleRepository repository;

    public TrainingExampleService(TrainingExampleRepository repository) {
        this.repository = repository;
    }

    /** The current tenant's captured corrections, newest first. */
    public List<TrainingExample> listNewestFirst() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Deletes one example if the current tenant has it. Idempotent: an unknown or cross-tenant id
     * (invisible under RLS) is a no-op instead of the error a bare {@code deleteById} raises.
     */
    public void deleteIfPresent(Long id) {
        if (repository.existsById(id)) {
            repository.deleteById(id);
        }
    }
}
