package com.jizza.engine.security.workspace;

import com.jizza.engine.api.enums.JoinRequestStatus;
import com.jizza.engine.api.enums.ListingAction;
import com.jizza.engine.api.enums.ListingStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.PublicListingStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link PublicListingStore} on plain SQL. Stores what it is told; the rules live in the platform. */
@Component
public class JdbcPublicListingStore implements PublicListingStore {

  private static final String LISTING_COLUMNS = """
      select w.id, w.name, w.listing_status, w.listed_at, p.display_name, p.description, p.category,
             p.logo_url, p.website, p.approved_snapshot::text as snapshot, p.submitted_at,
             p.updated_at
        from workspaces w
        left join workspace_public_profiles p on p.workspace_id = w.id
      """;

  private static final String JOIN_COLUMNS = """
      select r.id, r.workspace_id, w.name as workspace_name, r.user_id, u.email, r.message,
             r.status, r.decided_by_user_id, r.created_at, r.decided_at
        from workspace_join_requests r
        join workspaces w on w.id = r.workspace_id
        join users u on u.id = r.user_id
      """;

  private static final RowMapper<Listing> LISTING = JdbcPublicListingStore::mapListing;
  private static final RowMapper<JoinRequest> JOIN = JdbcPublicListingStore::mapJoinRequest;

  private final JdbcTemplate jdbc;

  public JdbcPublicListingStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ── Listings ──────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public Optional<Listing> listing(UUID workspaceId) {
    return jdbc.query(LISTING_COLUMNS + " where w.id = ?", LISTING, workspaceId)
        .stream().findFirst();
  }

  @Override
  @Transactional
  public void saveDraft(UUID workspaceId, Profile draft) {
    jdbc.update("""
        insert into workspace_public_profiles
          (workspace_id, display_name, description, category, logo_url, website, updated_at)
        values (?, ?, ?, ?, ?, ?, now())
        on conflict (workspace_id) do update set display_name = excluded.display_name,
          description = excluded.description, category = excluded.category,
          logo_url = excluded.logo_url, website = excluded.website, updated_at = now()
        """, workspaceId, draft.displayName(), draft.description(), draft.category(),
        draft.logoUrl(), draft.website());
  }

  @Override
  @Transactional
  public void setStatus(UUID workspaceId, ListingStatus status) {
    jdbc.update("update workspaces set listing_status = ? where id = ?", lower(status), workspaceId);
  }

  @Override
  @Transactional
  public void markSubmitted(UUID workspaceId, Instant at) {
    jdbc.update("update workspace_public_profiles set submitted_at = ? where workspace_id = ?",
        Timestamp.from(at), workspaceId);
  }

  @Override
  @Transactional
  public void publish(UUID workspaceId, Profile snapshot, Instant listedAt) {
    jdbc.update("update workspace_public_profiles set approved_snapshot = ?::jsonb "
        + "where workspace_id = ?", snapshot == null ? null : Json.write(snapshot), workspaceId);
    jdbc.update("update workspaces set listed_at = ? where id = ?",
        listedAt == null ? null : Timestamp.from(listedAt), workspaceId);
  }

  @Override
  @Transactional
  public void recordReview(UUID workspaceId, ListingAction action, UUID actorUserId, String note) {
    jdbc.update("insert into workspace_listing_reviews (workspace_id, action, actor_user_id, note) "
        + "values (?, ?, ?, ?)", workspaceId, lower(action), actorUserId, note);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Review> history(UUID workspaceId) {
    return jdbc.query("""
        select r.id, r.workspace_id, r.action, r.actor_user_id, u.email, r.note, r.created_at
          from workspace_listing_reviews r
          left join users u on u.id = r.actor_user_id
         where r.workspace_id = ?
         order by r.created_at desc, r.id
        """, (rs, i) -> new Review(uuid(rs, "id"), uuid(rs, "workspace_id"),
        ListingAction.valueOf(rs.getString("action").toUpperCase(Locale.ROOT)),
        uuid(rs, "actor_user_id"), rs.getString("email"), rs.getString("note"),
        instant(rs, "created_at")), workspaceId);
  }

  @Override
  @Transactional(readOnly = true)
  public long countReviews(UUID workspaceId, ListingAction action, Instant since) {
    Long n = jdbc.queryForObject("select count(*) from workspace_listing_reviews "
        + "where workspace_id = ? and action = ? and created_at >= ?", Long.class,
        workspaceId, lower(action), Timestamp.from(since));
    return n == null ? 0 : n;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Listing> byStatus(ListingStatus status) {
    return jdbc.query(LISTING_COLUMNS + " where w.type = 'organization' and w.listing_status = ? "
        + "order by p.submitted_at nulls last, w.name", LISTING, lower(status));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<Listing> searchListed(String query, String category, int offset, int limit) {
    StringBuilder where = new StringBuilder(" where w.type = 'organization' "
        + "and w.listing_status in ('approved','pending') and p.approved_snapshot is not null");
    List<Object> args = new ArrayList<>();
    if (query != null && !query.isBlank()) {
      where.append(" and (p.approved_snapshot->>'displayName' ilike ? "
          + "or p.approved_snapshot->>'description' ilike ?)");
      String like = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%")
          .replace("_", "\\_") + "%";
      args.add(like);
      args.add(like);
    }
    if (category != null && !category.isBlank()) {
      where.append(" and p.approved_snapshot->>'category' = ?");
      args.add(category.trim());
    }
    Long total = jdbc.queryForObject("select count(*) from workspaces w "
        + "join workspace_public_profiles p on p.workspace_id = w.id" + where, Long.class,
        args.toArray());
    List<Object> paged = new ArrayList<>(args);
    paged.add(limit);
    paged.add(offset);
    List<Listing> items = jdbc.query(LISTING_COLUMNS + where
        + " order by lower(p.approved_snapshot->>'displayName'), w.id limit ? offset ?",
        LISTING, paged.toArray());
    return new Page<>(items, total == null ? 0 : total);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> superAdmins() {
    return jdbc.query("select id from users where platform_role = 'super_admin'",
        (rs, i) -> uuid(rs, "id"));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> platformOrganization() {
    return jdbc.query("select id from workspaces where platform_public", (rs, i) -> uuid(rs, "id"))
        .stream().findFirst();
  }

  @Override
  @Transactional
  public void markPlatformOrganization(UUID workspaceId) {
    jdbc.update("update workspaces set platform_public = true where id = ?", workspaceId);
  }

  // ── Join requests ─────────────────────────────────────────────────────────

  @Override
  @Transactional
  public JoinRequest createJoinRequest(UUID workspaceId, UUID userId, String message) {
    UUID id = jdbc.queryForObject("insert into workspace_join_requests (workspace_id, user_id, "
        + "message) values (?, ?, ?) returning id", UUID.class, workspaceId, userId, message);
    return joinRequest(id).orElseThrow();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<JoinRequest> joinRequest(UUID requestId) {
    return jdbc.query(JOIN_COLUMNS + " where r.id = ?", JOIN, requestId)
        .stream().findFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<JoinRequest> pendingJoinRequest(UUID workspaceId, UUID userId) {
    return jdbc.query(JOIN_COLUMNS + " where r.workspace_id = ? and r.user_id = ? "
        + "and r.status = 'pending'", JOIN, workspaceId, userId).stream().findFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public List<JoinRequest> joinRequests(UUID workspaceId) {
    return jdbc.query(JOIN_COLUMNS + " where r.workspace_id = ? order by r.created_at desc",
        JOIN, workspaceId);
  }

  @Override
  @Transactional
  public void setJoinRequestStatus(UUID requestId, JoinRequestStatus status, UUID decidedByUserId) {
    jdbc.update("update workspace_join_requests set status = ?, decided_by_user_id = ?, "
        + "decided_at = now() where id = ?", lower(status), decidedByUserId, requestId);
  }

  // ── Mapping ───────────────────────────────────────────────────────────────

  private static Listing mapListing(ResultSet rs, int row) throws SQLException {
    Profile draft = rs.getString("display_name") == null ? null : new Profile(
        rs.getString("display_name"), rs.getString("description"), rs.getString("category"),
        rs.getString("logo_url"), rs.getString("website"));
    String snapshot = rs.getString("snapshot");
    Profile approved = snapshot == null ? null : Json.convert(Json.read(snapshot), Profile.class);
    return new Listing(uuid(rs, "id"), rs.getString("name"),
        ListingStatus.valueOf(rs.getString("listing_status").toUpperCase(Locale.ROOT)), draft,
        approved, instant(rs, "submitted_at"), instant(rs, "listed_at"),
        instant(rs, "updated_at"));
  }

  private static JoinRequest mapJoinRequest(ResultSet rs, int row) throws SQLException {
    return new JoinRequest(uuid(rs, "id"), uuid(rs, "workspace_id"),
        rs.getString("workspace_name"), uuid(rs, "user_id"), rs.getString("email"),
        rs.getString("message"),
        JoinRequestStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
        uuid(rs, "decided_by_user_id"), instant(rs, "created_at"), instant(rs, "decided_at"));
  }

  private static UUID uuid(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, UUID.class);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static String lower(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT);
  }
}
