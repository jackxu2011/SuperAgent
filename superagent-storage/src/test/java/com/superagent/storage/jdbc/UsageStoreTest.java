package com.superagent.storage.jdbc;

import com.superagent.core.usage.UsageRecord;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(InCompany.class)
class UsageStoreTest {

    @Test
    void keepsUsageByTaskAndByPersonOverTime() {
        JdbcUsageStore store = new JdbcUsageStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:usage-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")), JdbcUsageStore.MYSQL_DDL);
        Instant t = Instant.parse("2026-11-07T00:00:00Z");
        store.save(new UsageRecord("a", t, "ada", "task", "dev", "t1", "p1", "s1", "claude", "opus", 100, 2000, 300, 0, 50, 10, 2400, 0.12, 0.13));
        store.save(new UsageRecord("b", t.plusSeconds(60), "ada", "chat", "dev", null, "p1", "s2", "codex", "gpt", 10, 0, 0, 0, 5, 0, 10, null, null));
        store.save(new UsageRecord("c", t.plusSeconds(120), "bob", "task", null, "t2", null, null, "claude", "haiku", 1, 0, 0, 0, 1, 0, 1, 0.0, null));

        assertThat(store.forTask("t1")).singleElement().satisfies(r -> {
            assertThat(r.total()).isEqualTo(2450);
            assertThat(r.costUsd()).isEqualTo(0.12);
            assertThat(r.projectId()).isEqualTo("p1");
        });
        assertThat(store.between("ada", t, t.plusSeconds(3600))).extracting(UsageRecord::id).containsExactly("a", "b");
        assertThat(store.between(null, t.plusSeconds(1), t.plusSeconds(3600))).extracting(UsageRecord::id).containsExactly("b", "c");
        assertThat(store.between("ada", t, t.plusSeconds(3600)).get(1).costUsd()).isNull();

        assertThat(store.latestChatTurn("s2").id()).isEqualTo("b");
        assertThat(store.latestChatTurn("s2").contextTokens()).isEqualTo(10);
        assertThat(store.latestChatTurn("s1")).isNull(); // a task's record isn't a chat turn

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.latestChatTurn("s2")).isNull();
            assertThat(store.forTask("t1")).isEmpty();
            assertThat(store.between(null, t, t.plusSeconds(3600))).isEmpty();
        });
    }

    @Test
    void upgradesExistingTableWithProjectIdAndIndex() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:usage-upgrade-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        TenantSchema.create(jdbc, JdbcUsageStore.MYSQL_DDL_BEFORE_PROJECT);
        assertThat(columnPresent(jdbc, "project_id")).isZero();

        // CREATE IF NOT EXISTS leaves the old table; ensure* must add the column (and index on MySQL).
        JdbcUsageStore store = new JdbcUsageStore(jdbc, JdbcUsageStore.MYSQL_DDL);
        assertThat(columnPresent(jdbc, "project_id")).isEqualTo(1);

        Instant t = Instant.parse("2026-11-07T00:00:00Z");
        store.save(new UsageRecord("u1", t, "ada", "task", null, "t1", "p9", null, "claude", "m", 1, 0, 0, 0, 0, 0, 0, 0.01, null));
        assertThat(store.forTask("t1")).singleElement().extracting(UsageRecord::projectId).isEqualTo("p9");

        // Second boot must not fail when the column/index are already there.
        new JdbcUsageStore(jdbc, JdbcUsageStore.MYSQL_DDL);
    }

    @Test
    void failedProjectColumnMigrationIsNotSwallowed() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:usage-fail-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            @Override
            public void execute(String sql) {
                if (sql != null && sql.contains("ADD COLUMN project_id")) {
                    throw new org.springframework.jdbc.BadSqlGrammarException("alter", sql,
                            new java.sql.SQLException("permission denied", "42000", 1142));
                }
                super.execute(sql);
            }
        };
        TenantSchema.create(jdbc, JdbcUsageStore.MYSQL_DDL_BEFORE_PROJECT);
        assertThatThrownBy(() -> new JdbcUsageStore(jdbc, JdbcUsageStore.MYSQL_DDL))
                .isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class);
    }

    @Test
    void alreadyExistsErrorsAreRecognized() {
        assertThat(JdbcUsageStore.alreadyExists(new java.sql.SQLException("Duplicate column name 'project_id'", "42S21", 1060)))
                .isTrue();
        assertThat(JdbcUsageStore.alreadyExists(new java.sql.SQLException("Duplicate key name 'idx_usage_project_at'", "42000", 1061)))
                .isTrue();
        assertThat(JdbcUsageStore.alreadyExists(new java.sql.SQLException("permission denied", "42000", 1142))).isFalse();
    }

    @Test
    void clickHouseSkipsAlterWhenColumnAlreadyPresent() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        // TenantSchema.isLegacy probes; treat as "table exists with company_id".
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("SELECT 1 FROM token_usage")))
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("SELECT company_id FROM token_usage")))
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.contains("system.columns"),
                org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.eq("project_id"))).thenReturn(1);
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.contains("system.columns"),
                org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.eq("context_tokens"))).thenReturn(1);

        new JdbcUsageStore(jdbc, JdbcUsageStore.CLICKHOUSE_DDL);

        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).execute(
                org.mockito.ArgumentMatchers.contains("ADD COLUMN"));
    }

    @Test
    void clickHouseAlterFailureWhenColumnMissingIsNotSwallowed() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("SELECT 1 FROM token_usage")))
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("SELECT company_id FROM token_usage")))
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.contains("system.columns"),
                org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.eq("project_id"))).thenReturn(0);
        org.mockito.Mockito.doThrow(new org.springframework.jdbc.BadSqlGrammarException("alter", "ADD COLUMN",
                        new java.sql.SQLException("Not enough privileges", "42000", 497)))
                .when(jdbc).execute(org.mockito.ArgumentMatchers.contains("ADD COLUMN"));

        assertThatThrownBy(() -> new JdbcUsageStore(jdbc, JdbcUsageStore.CLICKHOUSE_DDL))
                .isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class);
    }

    @Test
    void addsTheContextColumnToATableMadeBeforeIt() {
        String url = "jdbc:h2:mem:usage-old-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        // The table as it was before context_tokens existed: CREATE TABLE IF NOT EXISTS won't change it.
        String before = JdbcUsageStore.MYSQL_DDL.lines()
                .filter(l -> !l.contains("context_tokens"))
                .reduce((a, b) -> a + "\n" + b).orElseThrow();
        jdbc.execute(before);
        jdbc.update("INSERT INTO token_usage (company_id, id, at, user_name, source, agent_id, task_id, project_id, "
                        + "session_id, cli, model, input_tokens, cache_read, cache_write, cache_write_1h, output_tokens, "
                        + "reasoning, cost_usd, cli_cost_usd) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                InCompany.ID, "old", java.time.LocalDateTime.parse("2026-11-20T00:00:00"), "ada", "chat", "dev", null, null,
                "s8", "claude", "opus", 10, 30_000, 0, 0, 5, 0, null, null);

        JdbcUsageStore store = new JdbcUsageStore(jdbc, JdbcUsageStore.MYSQL_DDL);
        // The row made before the column existed has no single-request figure: 0, so only turns decide for it.
        assertThat(store.latestChatTurn("s8").contextTokens()).isZero();
        store.save(new UsageRecord("a", Instant.parse("2026-11-21T00:00:00Z"), "ada", "chat", "dev", null, null, "s8",
                "claude", "opus", 10, 30_000, 0, 0, 5, 0, 30_010, null, null));
        assertThat(store.latestChatTurn("s8").contextTokens()).isEqualTo(30_010);
    }

    private static int columnPresent(JdbcTemplate jdbc, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                        + "AND LOWER(table_name) = 'token_usage' AND LOWER(column_name) = LOWER(?)",
                Integer.class, column);
        return n == null ? 0 : n;
    }
}
