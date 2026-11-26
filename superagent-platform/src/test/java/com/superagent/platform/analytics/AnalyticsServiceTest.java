package com.superagent.platform.analytics;

import com.superagent.api.analytics.AnalyticsDto;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.automation.AutomationStore;
import com.superagent.core.chat.AgentType;
import com.superagent.core.project.Project;
import com.superagent.core.project.ProjectAccess;
import com.superagent.core.project.ProjectItemKind;
import com.superagent.core.project.ProjectStatus;
import com.superagent.core.project.ProjectStore;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import com.superagent.core.user.UserStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {

    @Test
    void overviewGroupsTaskSpendByProjectIncludingUnassigned() {
        Instant now = Instant.now();
        Task inAlpha = task("t1", "m1", "1.50", now);
        Task alsoAlpha = task("t2", "m1", "0.50", now);
        Task nowhere = task("t3", "m2", "2.00", now);

        TaskStore tasks = mock(TaskStore.class);
        when(tasks.listTasks()).thenReturn(List.of(inAlpha, alsoAlpha, nowhere));
        UserStore users = mock(UserStore.class);
        when(users.listUsers()).thenReturn(List.of());
        AgentStore agents = mock(AgentStore.class);
        when(agents.list()).thenReturn(List.of());
        AutomationStore automations = mock(AutomationStore.class);
        when(automations.list()).thenReturn(List.of());

        ProjectAccess access = mock(ProjectAccess.class);
        when(access.projectsOfKind(ProjectItemKind.TASK)).thenReturn(Map.of("t1", "p1", "t2", "p1"));
        ProjectStore projectStore = mock(ProjectStore.class);
        when(projectStore.list()).thenReturn(List.of(
                new Project("p1", "Alpha", null, "ada", ProjectStatus.ACTIVE, now, now, null)));

        AnalyticsService service = new AnalyticsService(users, agents, automations, tasks);
        service.setProjects(access);
        service.setProjectStore(projectStore);

        AnalyticsDto dto = service.overview(14);

        assertThat(dto.spend()).isEqualByComparingTo("4.00");
        assertThat(dto.projects()).hasSize(2);
        AnalyticsDto.ProjectUsage alpha = dto.projects().stream().filter(p -> "p1".equals(p.projectId())).findFirst().orElseThrow();
        assertThat(alpha.name()).isEqualTo("Alpha");
        assertThat(alpha.tasks()).isEqualTo(2);
        assertThat(alpha.spend()).isEqualByComparingTo("2.00");
        AnalyticsDto.ProjectUsage none = dto.projects().stream().filter(p -> p.projectId() == null).findFirst().orElseThrow();
        assertThat(none.name()).isNull();
        assertThat(none.tasks()).isEqualTo(1);
        assertThat(none.spend()).isEqualByComparingTo("2.00");
    }

    private static Task task(String id, String model, String spend, Instant now) {
        return new Task(id, id, "brief", TaskSource.TASK_FORM, null, null, "general", TaskStatus.DONE, 100,
                new BigDecimal(spend), model, AgentType.CLAUDE, "/ws", "ada", null, now, now, now, now, null);
    }
}
