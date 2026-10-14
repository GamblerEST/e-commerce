package ee.shop.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Stripe guarantees AT-LEAST-once webhook delivery, never exactly-once — see
 * V13__payments.sql. eventId (Stripe's own event id, e.g. "evt_...") is the
 * primary key on purpose: the atomic claim IS the unique-constraint INSERT,
 * same idiom as orders.idempotency_key.
 *
 * Implements Persistable and hardcodes isNew()=true so repository.save()
 * always calls entityManager.persist() (a real INSERT that trips the unique
 * constraint on a replay) and never entityManager.merge() — Spring Data's
 * default isNew() check is "is the @Id null", which is always false here
 * since the id is Stripe's event id, assigned before save() is ever called.
 * merge() would silently SELECT-then-upsert instead, and a replayed webhook
 * would never throw the DataIntegrityViolationException the dedup in
 * StripeWebhookController relies on.
 */
@Entity
@Table(name = "processed_stripe_events")
public class ProcessedStripeEvent implements Persistable<String> {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedStripeEvent() {} // JPA

    public ProcessedStripeEvent(String eventId) {
        this.eventId = eventId;
    }


    @Override
    public String getId() { return eventId; }

    @Override
    public boolean isNew() { return true; }
}
