package org.cj.server.board.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.board.repository.CardRepository;

/**
 * Empties the bin on a timer: any card binned longer than {@link #RETENTION} is deleted for
 * good. This is what makes the guarantee precise — a binned card is restorable for
 * <b>at least</b> two days, and no code path can shorten that. There is deliberately no
 * "delete for ever now" endpoint, because one would let a user destroy something inside the
 * window the bin exists to protect.
 *
 * <p>Purging is <b>silent</b>: no {@code BoardChangedEvent}, so nothing is broadcast and nothing
 * is written to the activity log. The card already left every client's board (and was already
 * logged) when it was binned; announcing its final removal would tell subscribers to remove
 * something they stopped showing two days ago.
 *
 * <p>The window is a constant rather than configuration on purpose — it is a product promise the
 * UI states out loud ("restorable for 2 days"), not a per-environment knob. {@code purgeAt} in
 * {@code BinnedCardResponse} is derived from this same value, so the two can never disagree.
 */
@Component
public class BinPurgeJob {

    /** How long a binned card stays restorable. */
    public static final Duration RETENTION = Duration.ofDays(2);

    private static final Logger log = LoggerFactory.getLogger(BinPurgeJob.class);

    private final CardRepository cards;

    public BinPurgeJob(CardRepository cards) {
        this.cards = cards;
    }

    /**
     * Runs hourly. The cadence only sets how long past the window a card lingers, never how soon
     * it can go — the cutoff is computed from {@link #RETENTION}, so an hourly sweep means cards
     * live two days plus at most an hour, which errs in the safe direction.
     *
     * <p>{@code fixedDelay} (not {@code fixedRate}) so a slow purge can never overlap itself, and
     * the first sweep waits a minute so application startup isn't competing with it.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(RETENTION);
        long purged = cards.deleteByDeletedAtBefore(cutoff);
        if (purged > 0) {
            log.info("Bin purge: permanently deleted {} card(s) binned before {}", purged, cutoff);
        }
    }
}
