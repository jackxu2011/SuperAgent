package com.superagent.task.harness.coding;

import com.superagent.task.harness.HarnessState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the flow reads back from git in one call each: a snapshot of the worktree, and the changes since the start. */
class WorkspaceSnapshotTest {

    @TempDir
    Path dir;

    private final Git git = new Git(Map.of("PATH", System.getenv("PATH")));

    @Test
    void aReviewersEditsRenamesAndNewFilesAreSeenAndUndoneButTheCliSessionFilesAreNot() throws Exception {
        HarnessState s = repo();
        Workspace ws = new Workspace(git);
        Files.writeString(dir.resolve("a.txt"), "edited\n"); // the developer's work, not yet committed
        Workspace.Snapshot before = ws.snapshot(s);
        assertThat(before.head()).matches("[0-9a-f]{40}");

        Files.createDirectories(dir.resolve(".superagent"));
        Files.writeString(dir.resolve(".superagent/session.json"), "{}");
        assertThat(ws.restore(s, before)).isFalse(); // the CLI's own files don't count

        Files.writeString(dir.resolve("a.txt"), "the reviewer's\n");
        git.text(dir, "mv", "with space.txt", "moved file.txt");
        Files.writeString(dir.resolve("new note.md"), "scratch\n");
        assertThat(ws.restore(s, before)).isTrue();
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("a\n"); // back to the commit: a reviewer only reads
        assertThat(dir.resolve("with space.txt")).exists();
        assertThat(dir.resolve("moved file.txt")).doesNotExist();
        assertThat(dir.resolve("new note.md")).doesNotExist();
        assertThat(dir.resolve(".superagent/session.json")).exists();
    }

    @Test
    void eachChangedFileKeepsItsOwnPatchARenameIncluded() throws Exception {
        HarnessState s = repo();
        Files.writeString(dir.resolve("a.txt"), "a\nmore\n");
        git.text(dir, "mv", "with space.txt", "moved file.txt");
        Files.writeString(dir.resolve("b.txt"), "diff --git looks like a header but is content\n");
        git.text(dir, "add", "-A");
        git.text(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "work");

        new Workspace(git).recordChanges(s);

        assertThat(s.files).extracting(HarnessState.ChangedFile::path)
                .containsExactly("a.txt", "b.txt", "with space.txt => moved file.txt");
        assertThat(s.patches.get("a.txt")).startsWith("diff --git a/a.txt b/a.txt").contains("+more").doesNotContain("b.txt");
        assertThat(s.patches.get("b.txt")).startsWith("diff --git a/b.txt").contains("+diff --git looks like a header");
        assertThat(s.patches.get("with space.txt => moved file.txt")).contains("rename to moved file.txt");
        assertThat(s.patches.values()).allMatch(p -> p.endsWith("\n"));
    }

    @Test
    void thePlatformFolderNeverBecomesPartOfTheTasksChangeEvenWhenTheAgentCommittedIt() throws Exception {
        // A repository that tracks a file under .superagent/ and doesn't ignore the folder.
        Files.createDirectories(dir.resolve(".superagent/skills/a"));
        Files.writeString(dir.resolve(".superagent/skills/a/SKILL.md"), "tracked skill\n");
        HarnessState s = repo();
        // The run: installing skills wipes the tracked one, the CLI writes its session file; the agent commits it all.
        Files.delete(dir.resolve(".superagent/skills/a/SKILL.md"));
        Files.createDirectories(dir.resolve(".superagent/skills/b"));
        Files.writeString(dir.resolve(".superagent/skills/b/SKILL.md"), "installed\n");
        Files.writeString(dir.resolve(".superagent/claude-session-1"), "session\n");
        Files.writeString(dir.resolve("a.txt"), "work\n");
        git.text(dir, "add", "-A");
        git.text(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "the agent's own commit");
        Files.writeString(dir.resolve("a.txt"), "more work\n");

        assertThat(new Workspace(git).commitAll(s, "platform")).isTrue();

        assertThat(git.text(dir, "diff", "--name-only", s.baseSha + "..HEAD")).isEqualTo("a.txt");
        assertThat(git.text(dir, "status", "--porcelain", "--untracked-files=no")).isEmpty(); // clean for a rebase
        assertThat(dir.resolve(".superagent/claude-session-1")).exists(); // the CLI still needs it
        assertThat(dir.resolve(".superagent/skills/b/SKILL.md")).exists();
    }

    @Test
    void aRepositoryWithoutThePlatformFolderCommitsAsBefore() throws Exception {
        HarnessState s = repo();
        Workspace ws = new Workspace(git);
        assertThat(ws.commitAll(s, "nothing")).isFalse();
        Files.writeString(dir.resolve("a.txt"), "changed\n");
        assertThat(ws.commitAll(s, "work")).isTrue();
        assertThat(git.text(dir, "diff", "--name-only", s.baseSha + "..HEAD")).isEqualTo("a.txt");
    }

    private HarnessState repo() throws Exception {
        git.text(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("a.txt"), "a\n");
        Files.writeString(dir.resolve("with space.txt"), "b\n");
        git.text(dir, "add", "-A");
        git.text(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
        HarnessState s = new HarnessState();
        s.dir = dir.toString();
        s.baseSha = git.text(dir, "rev-parse", "HEAD");
        return s;
    }
}
