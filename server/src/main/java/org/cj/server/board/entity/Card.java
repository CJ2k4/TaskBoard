package org.cj.server.board.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A card within a column. Maps to {@code card}.
 *
 * <p>Note the two foreign keys: {@code columnId} (its column) and {@code boardId} (its board).
 * {@code boardId} is <b>denormalized</b> — derivable via the column — but kept directly so
 * board-scoped reads and auth checks don't have to join through columns. It must be kept in
 * sync on cross-column moves (M3): the target column must belong to the same board.
 *
 * <p>{@code rank} is a LexoRank string positioning the card within its column.
 */
@Entity
@Table(name = "card")
public class Card {

    @Id
    private UUID id;

    @Column(name = "column_id", nullable = false)
    private UUID columnId;

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(nullable = false, length = 280)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /** Optional short free-text tag rendered as a chip (e.g. "BACKEND"). */
    @Column(length = 40)
    private String label;

    /** Optional assignee — an app_user id. Kept as a bare id (not a JPA relation) so the
     *  board aggregate load stays join-free; the client resolves the name from the member list. */
    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(nullable = false, length = 64)
    private String rank;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * When this card was moved to the bin, or null while it is live. This is the soft-delete
     * marker: every board-facing read filters on it being null, so a binned card vanishes from
     * the board without the row going away, and stays restorable until the retention job
     * purges it.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Who binned it — shown in the bin. Null once that user is deleted (FK is SET NULL). */
    @Column(name = "deleted_by")
    private UUID deletedBy;

    protected Card() {
    }

    private Card(UUID id, UUID columnId, UUID boardId, String title, String description,
                String label, UUID assigneeId, String rank, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.columnId = columnId;
        this.boardId = boardId;
        this.title = title;
        this.description = description;
        this.label = label;
        this.assigneeId = assigneeId;
        this.rank = rank;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * A new card in {@code columnId} (belonging to {@code boardId}) at a server-computed rank.
     * New cards start with no label and no assignee — both are set later via {@link #edit}.
     */
    public static Card create(UUID columnId, UUID boardId, String title, String description, String rank) {
        Instant now = Instant.now();
        return new Card(UUID.randomUUID(), columnId, boardId, title, description, null, null, rank, now, now);
    }

    /** Edit the last-write-wins fields (title/description/label/assignee), bumping {@code updatedAt}. */
    public void edit(String title, String description, String label, UUID assigneeId) {
        this.title = title;
        this.description = description;
        this.label = label;
        this.assigneeId = assigneeId;
        this.updatedAt = Instant.now();
    }

    /**
     * Move this card to a column (possibly its current one) at a server-computed rank. A move
     * is a user action, so {@code updatedAt} is bumped. The caller must have verified the
     * target column belongs to this card's board — {@code boardId} does not change.
     */
    public void moveTo(UUID columnId, String rank) {
        this.columnId = columnId;
        this.rank = rank;
        this.updatedAt = Instant.now();
    }

    /**
     * Reassign the rank during a re-balance. Deliberately does <b>not</b> bump
     * {@code updatedAt}: re-spacing is server bookkeeping, not a user edit, and must never
     * win a future last-write-wins conflict (M5) on behalf of an untouched card.
     */
    public void rebalanceRank(String rank) {
        this.rank = rank;
    }

    /**
     * Move this card to the bin. The column, rank and every field are left untouched so a
     * restore can put it back exactly where it was; only the two bin markers are set.
     * {@code updatedAt} is deliberately <b>not</b> bumped — binning isn't an edit of the card's
     * content, and must not win a later last-write-wins conflict against a real edit.
     */
    public void moveToBin(UUID actorId) {
        this.deletedAt = Instant.now();
        this.deletedBy = actorId;
    }

    /**
     * Bring this card back out of the bin at a freshly computed rank — its old rank may well
     * have been taken by a card created while it was away, so the caller appends it. This one
     * <em>does</em> bump {@code updatedAt}: a restore puts a card back in front of everyone, so
     * it should read as a change.
     */
    public void restore(String rank) {
        this.deletedAt = null;
        this.deletedBy = null;
        this.rank = rank;
        this.updatedAt = Instant.now();
    }

    /** Whether this card is sitting in the bin rather than on the board. */
    public boolean isBinned() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getColumnId() {
        return columnId;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getLabel() {
        return label;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public String getRank() {
        return rank;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public UUID getDeletedBy() {
        return deletedBy;
    }
}
