package com.superagent.task.harness.coding;

import com.superagent.task.harness.HarnessState.ChangedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommitMessageTest {

    private static final List<ChangedFile> CODE = List.of(new ChangedFile("superagent-task/src/main/java/A.java", 5, 1));

    @ParameterizedTest
    @CsvSource({
            "修复 MCP 偶发报错, fix",
            "Fix the merge, fix",
            "修复测试不稳定, fix",
            "更新 README, docs",
            "补充测试, test",
            "重构会话池, refactor",
            "性能优化, perf",
            "统一代码格式, style",
            "升级依赖, chore",
            "新增导出按钮, feat",
    })
    void typeFromTitleKeywords(String title, String type) {
        assertThat(CommitMessage.type(title, CODE)).isEqualTo(type);
    }

    @Test
    void typeFallsBackOnTheChangedFiles() {
        assertThat(CommitMessage.type("调整说明", List.of(new ChangedFile("README.md", 3, 0),
                new ChangedFile("docs/a.MD", 1, 1), new ChangedFile(".superagent/skills/x/run.json", 9, 0)))).isEqualTo("docs");
        assertThat(CommitMessage.type("覆盖边界", List.of(new ChangedFile("superagent-task/src/test/java/ATest.java", 9, 0))))
                .isEqualTo("test");
        assertThat(CommitMessage.type("调整", List.of(new ChangedFile("README.md", 1, 0),
                new ChangedFile("superagent-task/src/test/java/ATest.java", 1, 0)))).isEqualTo("feat");
        assertThat(CommitMessage.type("调整", List.of())).isEqualTo("feat");
    }

    @Test
    void scopeIsTheMainModuleElseTheTopDirectoryElseRepo() {
        assertThat(CommitMessage.scope(List.of(new ChangedFile("superagent-task/A.java", 10, 0),
                new ChangedFile("superagent-runtime/B.java", 100, 20), new ChangedFile("scripts/run.sh", 500, 0),
                new ChangedFile(".superagent/skills/s/SKILL.md", 900, 0)))).isEqualTo("runtime");
        assertThat(CommitMessage.scope(List.of(new ChangedFile("scripts/run.sh", 3, 1), new ChangedFile("pom.xml", 50, 0))))
                .isEqualTo("scripts");
        assertThat(CommitMessage.scope(List.of(new ChangedFile("pom.xml", 2, 2),
                new ChangedFile(".superagent/skills/s/SKILL.md", 9, 0)))).isEqualTo("repo");
        assertThat(CommitMessage.scope(List.of())).isEqualTo("repo");
        assertThat(CommitMessage.scope(List.of(new ChangedFile("{old => superagent-files}/A.java", 1, 1)))).isEqualTo("files");
    }

    @Test
    void conventionalTitleIsKeptAsItIs() {
        assertThat(CommitMessage.of("fix(task): 修复 X", "t1", List.of(), CODE)).startsWith("fix(task): 修复 X\n\n");
        assertThat(CommitMessage.of("feat!: 新接口", "t1", List.of(), CODE)).startsWith("feat!: 新接口\n\n");
    }

    @Test
    void wholeMessage() {
        assertThat(CommitMessage.of("  修复 X \n", "t-42", List.of("标准一", "标准二"), CODE))
                .isEqualTo("fix(task): 修复 X\n\n- 标准一\n- 标准二\n\nSuperAgent task t-42");
    }
}
