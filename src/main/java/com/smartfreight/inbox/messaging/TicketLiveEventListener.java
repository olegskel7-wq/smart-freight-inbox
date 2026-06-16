package com.smartfreight.inbox.messaging;

import com.smartfreight.inbox.entity.EmailTicket;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/**
 * JPA lifecycle listener that turns every {@link EmailTicket} write into a real-time broadcast
 * (Phase 3.2). Registered via {@code @EntityListeners} on the entity.
 *
 * <p>One hook covers the whole surface — ingest ({@code @PostPersist}), the AI pipeline and operator
 * actions ({@code @PostUpdate}) and soft-delete (Hibernate's {@code @SQLDelete} issues an UPDATE, but
 * JPA fires {@code @PostRemove} for {@code remove()}, so both are wired) — without touching any
 * service. It delegates to {@link TicketLiveEventService} (obtained via {@link TicketLiveEventBridge},
 * since a JPA listener is not a Spring bean), which defers the actual send to after commit.
 *
 * <p>Bulk JPQL/native updates do <i>not</i> fire entity callbacks, so the per-minute SLA-breach sweep
 * ({@code SlaBreachScheduler}'s bulk UPDATE) is not pushed live — an accepted gap (the breach badge
 * appears on the operator's next refresh/event), noted for a possible later explicit push.
 */
public class TicketLiveEventListener {

    @PostPersist
    @PostUpdate
    @PostRemove
    public void onTicketChange(EmailTicket ticket) {
        TicketLiveEventService service = TicketLiveEventBridge.service();
        if (service != null) {
            service.broadcast(ticket);
        }
    }
}
