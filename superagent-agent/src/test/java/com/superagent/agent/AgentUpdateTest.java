package com.superagent.agent;

import com.superagent.api.agent.UpdateAgentRequest;
import com.superagent.api.chat.SessionDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentMemberStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.platform.user.PeopleDirectory;
import com.superagent.skills.SkillService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** update: a field left out (null) keeps its value; an empty string clears it. */
class AgentUpdateTest {

    private final Map<String, Agent> rows = new LinkedHashMap<>();
    private final SkillService skills = mock(SkillService.class);
    private final AgentMemberStore members = mock(AgentMemberStore.class);
    private final PeopleDirectory people = mock(PeopleDirectory.class);
    private final AgentService agents = service();

    @Test
    void fieldsLeftOutKeepTheirValues() {
        rows.put("a1", agent("a1", AgentKind.STANDARD, "rigorous-engineer", "你是写作助手", "gpt-4o", "pro"));

        agents.update("a1", new UpdateAgentRequest("新名字", null, null, null, null, null, null), "alice");

        Agent after = rows.get("a1");
        assertThat(after.name()).isEqualTo("新名字");
        assertThat(after.persona()).isEqualTo("你是写作助手");
        assertThat(after.model()).isEqualTo("gpt-4o");
        assertThat(after.character()).isEqualTo("rigorous-engineer");
        assertThat(after.tier()).isEqualTo("pro");
        verify(skills, never()).setAgentSkills(any(), any(), any());
    }

    @Test
    void emptyStringsClear() {
        rows.put("a1", agent("a1", AgentKind.STANDARD, "rigorous-engineer", "你是写作助手", "gpt-4o", "pro"));

        agents.update("a1", new UpdateAgentRequest(null, "", "", null, "", null, ""), "alice");

        Agent after = rows.get("a1");
        assertThat(after.name()).isEqualTo("写手");          // a name can't be cleared
        assertThat(after.persona()).isNull();
        assertThat(after.model()).isNull();                  // back to the system default model
        assertThat(after.character()).isNull();              // a custom character
        assertThat(after.tier()).isNull();                   // back to the default tier
    }

    @Test
    void theSuperAgentKeepsItsModelWhenOnlyItsLookChanges() {
        rows.put("s1", agent("s1", AgentKind.SUPER, null, null, "gpt-4o", null));

        agents.update("s1", new UpdateAgentRequest(null, null, null, AgentService.AVATARS.get(0), null, null, null), "alice");

        assertThat(rows.get("s1").model()).isEqualTo("gpt-4o");
        assertThat(rows.get("s1").avatar()).isEqualTo(AgentService.AVATARS.get(0));
    }

    private AgentService service() {
        AgentStore store = new AgentStore() {
            @Override public Agent create(Agent a) { rows.put(a.id(), a); return a; }
            @Override public Optional<Agent> find(String id) { return Optional.ofNullable(rows.get(id)); }
            @Override public List<Agent> list() { return List.copyOf(rows.values()); }
            @Override public void update(Agent a) { rows.put(a.id(), a); }
        };
        com.superagent.agent.chat.ChatService chats = mock(com.superagent.agent.chat.ChatService.class);
        when(chats.createSession(any())).thenReturn(mock(SessionDto.class));
        when(members.members(any())).thenReturn(List.of());
        when(people.find(any())).thenReturn(Optional.empty());
        return new AgentService(store, chats, skills, null, members, null, null, people);
    }

    private static Agent agent(String id, AgentKind kind, String character, String persona, String model, String tier) {
        Instant now = Instant.now();
        return new Agent(id, "写手", null, character, persona, model, AgentStatus.ACTIVE, "alice", kind, null, now, now, tier);
    }
}
