package com.academy.chatservice.repository;

import com.academy.chatservice.model.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class AdminConversationRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminConversationRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminConversationPageDto findPage(AdminConversationFilters filters, int page, int size) {
        String where = buildWhereClause(filters);
        MapSqlParameterSource params = buildParams(filters)
                .addValue("limit", size)
                .addValue("offset", Math.max(page, 0) * size);

        String select = """
                SELECT c.id AS conversation_id,
                       COALESCE(NULLIF(c.title, ''), 'Nueva conversación') AS title,
                       c.user_email,
                       COALESCE(NULLIF(c.user_name, ''), split_part(c.user_email, '@', 1), c.user_email) AS user_name,
                       COALESCE(stats.message_count, 0) AS message_count,
                       c.created_at,
                       COALESCE(stats.last_activity, c.updated_at, c.created_at) AS last_activity,
                       COALESCE(SUBSTRING(last_msg.content FROM 1 FOR 150), '') AS last_message,
                       CASE
                         WHEN COALESCE(stats.last_activity, c.updated_at, c.created_at) >= CURRENT_DATE THEN TRUE
                         ELSE FALSE
                       END AS active
                FROM conversations c
                LEFT JOIN (
                    SELECT m.conversation_id,
                           COUNT(*) AS message_count,
                           MAX(m.created_at) AS last_activity
                    FROM messages m
                    GROUP BY m.conversation_id
                ) stats ON stats.conversation_id = c.id
                LEFT JOIN messages last_msg
                  ON last_msg.id = (
                      SELECT m2.id
                      FROM messages m2
                      WHERE m2.conversation_id = c.id
                      ORDER BY m2.created_at DESC, m2.id DESC
                      LIMIT 1
                  )
                """ + where + """
                ORDER BY COALESCE(stats.last_activity, c.updated_at, c.created_at) DESC, c.id DESC
                LIMIT :limit OFFSET :offset
                """;

        String count = """
                SELECT COUNT(*)
                FROM conversations c
                LEFT JOIN (
                    SELECT m.conversation_id,
                           MAX(m.created_at) AS last_activity
                    FROM messages m
                    GROUP BY m.conversation_id
                ) stats ON stats.conversation_id = c.id
                """ + where;

        List<AdminConversationSummaryDto> content = jdbcTemplate.query(select, params, this::mapSummaryRow);
        long totalElements = Optional.ofNullable(jdbcTemplate.queryForObject(count, params, Long.class)).orElse(0L);
        int totalPages = size <= 0 ? 1 : (int) Math.max(1, Math.ceil((double) totalElements / size));
        return new AdminConversationPageDto(content, totalElements, totalPages, page, size);
    }

    public AdminConversationMetricsDto fetchMetrics(AdminConversationFilters filters) {
        MapSqlParameterSource params = buildParams(filters);
        String where = buildWhereClause(filters);

        String totalSql = """
                SELECT COUNT(*)
                FROM conversations c
                LEFT JOIN (
                    SELECT m.conversation_id,
                           MAX(m.created_at) AS last_activity
                    FROM messages m
                    GROUP BY m.conversation_id
                ) stats ON stats.conversation_id = c.id
                """ + where;

        String activeTodaySql = """
                SELECT COUNT(*)
                FROM conversations c
                LEFT JOIN (
                    SELECT m.conversation_id,
                           MAX(m.created_at) AS last_activity
                    FROM messages m
                    GROUP BY m.conversation_id
                ) stats ON stats.conversation_id = c.id
                """ + where + """
                AND COALESCE(stats.last_activity, c.updated_at, c.created_at) >= CURRENT_DATE
                """;

        String uniqueUsersTodaySql = """
                SELECT COUNT(DISTINCT c.user_email)
                FROM conversations c
                LEFT JOIN (
                    SELECT m2.conversation_id,
                           MAX(m2.created_at) AS last_activity
                    FROM messages m2
                    GROUP BY m2.conversation_id
                ) stats ON stats.conversation_id = c.id
                JOIN messages m ON m.conversation_id = c.id
                """ + where + """
                AND m.created_at >= CURRENT_DATE
                """;

        String messagesTodaySql = """
                SELECT COUNT(*)
                FROM conversations c
                LEFT JOIN (
                    SELECT m2.conversation_id,
                           MAX(m2.created_at) AS last_activity
                    FROM messages m2
                    GROUP BY m2.conversation_id
                ) stats ON stats.conversation_id = c.id
                JOIN messages m ON m.conversation_id = c.id
                """ + where + """
                AND m.created_at >= CURRENT_DATE
                """;

        long total = Optional.ofNullable(jdbcTemplate.queryForObject(totalSql, params, Long.class)).orElse(0L);
        long activeToday = Optional.ofNullable(jdbcTemplate.queryForObject(activeTodaySql, params, Long.class)).orElse(0L);
        long uniqueUsersToday = Optional.ofNullable(jdbcTemplate.queryForObject(uniqueUsersTodaySql, params, Long.class)).orElse(0L);
        long messagesToday = Optional.ofNullable(jdbcTemplate.queryForObject(messagesTodaySql, params, Long.class)).orElse(0L);

        return new AdminConversationMetricsDto(total, activeToday, uniqueUsersToday, messagesToday);
    }

    public Optional<AdminConversationDetailDto> findDetail(Long conversationId) {
        String conversationSql = """
                SELECT c.id,
                       COALESCE(NULLIF(c.title, ''), 'Nueva conversación') AS title,
                       c.user_email,
                       COALESCE(NULLIF(c.user_name, ''), split_part(c.user_email, '@', 1), c.user_email) AS user_name,
                       c.created_at,
                       COALESCE(stats.last_activity, c.updated_at, c.created_at) AS last_activity,
                       COALESCE(stats.message_count, 0) AS message_count
                FROM conversations c
                LEFT JOIN (
                    SELECT m.conversation_id,
                           COUNT(*) AS message_count,
                           MAX(m.created_at) AS last_activity
                    FROM messages m
                    GROUP BY m.conversation_id
                ) stats ON stats.conversation_id = c.id
                WHERE c.hidden = FALSE
                  AND c.id = :conversationId
                """;

        List<AdminConversationDetailDto> details = jdbcTemplate.query(
                conversationSql,
                new MapSqlParameterSource("conversationId", conversationId),
                (rs, _rowNum) -> new AdminConversationDetailDto(
                        rs.getLong("id"),
                        rs.getString("title"),
                        new AdminConversationUserDto(
                                null,
                                rs.getString("user_name"),
                                rs.getString("user_email")
                        ),
                        toLocalDateTime(rs.getTimestamp("created_at")),
                        toLocalDateTime(rs.getTimestamp("last_activity")),
                        rs.getInt("message_count"),
                        List.of()
                )
        );

        if (details.isEmpty()) {
            return Optional.empty();
        }

        String messagesSql = """
                SELECT role, content, created_at
                FROM messages
                WHERE conversation_id = :conversationId
                ORDER BY created_at ASC, id ASC
                """;

        List<AdminConversationMessageDto> messages = jdbcTemplate.query(
                messagesSql,
                new MapSqlParameterSource("conversationId", conversationId),
                (rs, _rowNum) -> new AdminConversationMessageDto(
                        rs.getString("role").toUpperCase(Locale.ROOT),
                        rs.getString("content"),
                        toLocalDateTime(rs.getTimestamp("created_at"))
                )
        );

        AdminConversationDetailDto base = details.get(0);
        return Optional.of(new AdminConversationDetailDto(
                base.id(),
                base.title(),
                base.user(),
                base.createdAt(),
                base.lastActivity(),
                base.messageCount(),
                messages
        ));
    }

    private AdminConversationSummaryDto mapSummaryRow(ResultSet rs, int _rowNum) throws SQLException {
        return new AdminConversationSummaryDto(
                rs.getLong("conversation_id"),
                rs.getString("title"),
                rs.getString("user_email"),
                rs.getString("user_name"),
                rs.getInt("message_count"),
                toLocalDateTime(rs.getTimestamp("created_at")),
                toLocalDateTime(rs.getTimestamp("last_activity")),
                rs.getString("last_message"),
                rs.getBoolean("active")
        );
    }

    private String buildWhereClause(AdminConversationFilters filters) {
        return """
                WHERE c.hidden = FALSE
                  AND (COALESCE(:email, '') = '' OR LOWER(COALESCE(c.user_email, '')) LIKE :email)
                  AND (COALESCE(:name, '') = '' OR LOWER(COALESCE(c.user_name, split_part(c.user_email, '@', 1), c.user_email, '')) LIKE :name)
                  AND (COALESCE(:title, '') = '' OR LOWER(COALESCE(c.title, '')) LIKE :title)
                  AND COALESCE(stats.last_activity, c.updated_at, c.created_at) >= COALESCE(:fromDate, TIMESTAMP '1970-01-01')
                  AND COALESCE(stats.last_activity, c.updated_at, c.created_at) < COALESCE(:toDateExclusive, TIMESTAMP '9999-12-31')
                """;
    }

    private MapSqlParameterSource buildParams(AdminConversationFilters filters) {
        LocalDate from = filters.from();
        LocalDate to = filters.to();
        return new MapSqlParameterSource()
                .addValue("email", normalizeLike(filters.email()), Types.VARCHAR)
                .addValue("name", normalizeLike(filters.name()), Types.VARCHAR)
                .addValue("title", normalizeLike(filters.title()), Types.VARCHAR)
                .addValue("fromDate", from != null ? from.atStartOfDay() : null, Types.TIMESTAMP)
                .addValue("toDate", to != null ? to.atStartOfDay() : null, Types.TIMESTAMP)
                .addValue("toDateExclusive", to != null ? to.plusDays(1).atStartOfDay() : null, Types.TIMESTAMP);
    }

    private String normalizeLike(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp != null ? timestamp.toLocalDateTime() : null;
    }
}
