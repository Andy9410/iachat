package com.academy.chatservice.repository;

import com.academy.chatservice.model.LearningEvidenceSnapshot;
import com.academy.chatservice.model.LearningExerciseSignal;
import com.academy.chatservice.model.LearningProfileSignals;
import com.academy.chatservice.model.LearningQuestionSignal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class LearningProfileRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public LearningProfileRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public LearningEvidenceSnapshot fetchEvidence(String userEmail) {
        MapSqlParameterSource params = new MapSqlParameterSource("userEmail", userEmail);

        long documentsAnalyzed = valueOrZero("""
                SELECT COUNT(*)
                FROM documents d
                WHERE d.user_email = :userEmail
                  AND d.status = 'ready'
                """, params);

        long exercisesDetected = valueOrZero("""
                SELECT COUNT(DISTINCT CONCAT(de.document_id, '::', LOWER(BTRIM(de.metadata->>'exercise_ref'))))
                FROM document_embeddings de
                JOIN documents d ON d.id = de.document_id
                WHERE d.user_email = :userEmail
                  AND d.status = 'ready'
                  AND NULLIF(BTRIM(de.metadata->>'exercise_ref'), '') IS NOT NULL
                """, params);

        long relevantInteractions = valueOrZero("""
                SELECT COUNT(*)
                FROM messages m
                JOIN conversations c ON c.id = m.conversation_id
                WHERE c.user_email = :userEmail
                  AND c.hidden = FALSE
                  AND m.role = 'user'
                  AND NULLIF(BTRIM(m.content), '') IS NOT NULL
                """, params);

        return LearningEvidenceSnapshot.of(documentsAnalyzed, exercisesDetected, relevantInteractions);
    }

    public LearningProfileSignals fetchSignals(String userEmail) {
        MapSqlParameterSource params = new MapSqlParameterSource("userEmail", userEmail)
                .addValue("documentsLimit", 5)
                .addValue("exercisesLimit", 6)
                .addValue("questionsLimit", 8);

        List<String> recentDocuments = jdbcTemplate.query("""
                        SELECT COALESCE(d.filename, d.source)
                        FROM documents d
                        WHERE d.user_email = :userEmail
                          AND d.status = 'ready'
                        ORDER BY d.created_at DESC
                        LIMIT :documentsLimit
                        """,
                params,
                (rs, rowNum) -> rs.getString(1));

        List<LearningExerciseSignal> recentExercises = jdbcTemplate.query("""
                        SELECT COALESCE(d.filename, d.source) AS document_name,
                               LOWER(BTRIM(de.metadata->>'exercise_ref')) AS exercise_ref,
                               COUNT(*) AS chunk_count
                        FROM document_embeddings de
                        JOIN documents d ON d.id = de.document_id
                        WHERE d.user_email = :userEmail
                          AND d.status = 'ready'
                          AND NULLIF(BTRIM(de.metadata->>'exercise_ref'), '') IS NOT NULL
                        GROUP BY COALESCE(d.filename, d.source), LOWER(BTRIM(de.metadata->>'exercise_ref')), d.created_at, d.id
                        ORDER BY d.created_at DESC, d.id DESC, exercise_ref ASC
                        LIMIT :exercisesLimit
                        """,
                params,
                (rs, rowNum) -> new LearningExerciseSignal(
                        rs.getString("document_name"),
                        rs.getString("exercise_ref"),
                        rs.getInt("chunk_count")
                ));

        List<LearningQuestionSignal> recentQuestions = jdbcTemplate.query("""
                        SELECT COALESCE(c.title, '') AS conversation_title,
                               m.content AS question
                        FROM messages m
                        JOIN conversations c ON c.id = m.conversation_id
                        WHERE c.user_email = :userEmail
                          AND c.hidden = FALSE
                          AND m.role = 'user'
                          AND NULLIF(BTRIM(m.content), '') IS NOT NULL
                        ORDER BY m.created_at DESC
                        LIMIT :questionsLimit
                        """,
                params,
                (rs, rowNum) -> new LearningQuestionSignal(
                        rs.getString("conversation_title"),
                        rs.getString("question")
                ));

        return new LearningProfileSignals(
                List.copyOf(recentDocuments),
                List.copyOf(recentExercises),
                List.copyOf(recentQuestions)
        );
    }

    private long valueOrZero(String sql, MapSqlParameterSource params) {
        Long value = jdbcTemplate.queryForObject(sql, params, Long.class);
        return value != null ? value : 0L;
    }
}
