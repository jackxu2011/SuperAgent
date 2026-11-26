package com.superagent.task.harness.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** On Windows the checks run in Git for Windows' bash, found through git.exe; never the bash.exe that starts WSL. */
class GitShellTest {

    @TempDir
    Path dir;

    @Test
    void gitForWindowsBashIsFoundFromGitExeAndWslsBashIsPassedOver() throws Exception {
        Path wsl = touch(dir.resolve("Windows/System32/bash.exe"));
        touch(dir.resolve("Git/cmd/git.exe"));
        Path bash = touch(dir.resolve("Git/bin/bash.exe"));
        String path = String.join(File.pathSeparator, wsl.getParent().toString(), "", dir.resolve("Git/cmd").toString());

        assertThat(Git.gitBash(path)).contains(bash);
    }

    @Test
    void withoutGitForWindowsThereIsNoBashAndTheCheckFallsBackToCmd() throws Exception {
        touch(dir.resolve("Windows/System32/bash.exe"));
        String path = dir.resolve("Windows/System32").toString();

        assertThat(Git.gitBash(path)).isEmpty();
        assertThat(Git.shellCommand("mvn test", true, Optional.empty())).containsExactly("cmd.exe", "/c", "mvn test");
        assertThat(Git.shellCommand("mvn test", true, Optional.of(Path.of("C:/Git/bin/bash.exe"))))
                .containsExactly(Path.of("C:/Git/bin/bash.exe").toString(), "-c", "mvn test");
        assertThat(Git.shellCommand("mvn test", false, Optional.empty())).containsExactly("/bin/sh", "-c", "mvn test");
    }

    private static Path touch(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "");
    }
}
