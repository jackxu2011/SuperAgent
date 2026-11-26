package com.superagent.task.harness.coding;

import com.superagent.task.harness.HarnessState;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The message of a delivered task's commit: a Conventional Commits first line ({@code <type>(<scope>): <subject>}),
 * the acceptance criteria and the task id.
 */
final class CommitMessage {

    private static final Pattern CONVENTIONAL = Pattern.compile("^(feat|fix|docs|refactor|test|chore|perf|style)(\\([^)]+\\))?!?: ");

    /** In order: the first type whose keyword the title contains wins. English keywords are lower case here. */
    private static final List<Map.Entry<String, List<String>>> KEYWORDS = List.of(
            Map.entry("fix", List.of("修复", "修正", "fix", "bug")),
            Map.entry("docs", List.of("文档", "docs", "readme")),
            Map.entry("test", List.of("测试", "test")),
            Map.entry("refactor", List.of("重构", "refactor")),
            Map.entry("perf", List.of("性能", "perf")),
            Map.entry("style", List.of("格式", "style")),
            Map.entry("chore", List.of("构建", "依赖", "脚本", "chore")),
            Map.entry("feat", List.of("新增", "添加", "支持", "feat")));

    private static final String SESSION_DIR = ".superagent/";
    private static final String MODULE_PREFIX = "superagent-";

    private CommitMessage() {
    }

    static String of(String title, String taskId, List<String> criteria, List<HarnessState.ChangedFile> files) {
        String subject = title == null ? "" : title.strip();
        String first = CONVENTIONAL.matcher(subject).find() ? subject
                : type(subject, files) + "(" + scope(files) + "): " + subject;
        StringBuilder sb = new StringBuilder(first).append("\n\n");
        criteria.forEach(c -> sb.append("- ").append(c).append("\n"));
        return sb.append("\nSuperAgent task ").append(taskId).toString();
    }

    static String type(String title, List<HarnessState.ChangedFile> files) {
        String lower = title.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> e : KEYWORDS) {
            if (e.getValue().stream().anyMatch(lower::contains)) {
                return e.getKey();
            }
        }
        List<String> paths = paths(files);
        if (paths.isEmpty()) {
            return "feat";
        }
        if (paths.stream().allMatch(p -> p.toLowerCase(Locale.ROOT).endsWith(".md"))) {
            return "docs";
        }
        if (paths.stream().allMatch(p -> p.startsWith("src/test/") || p.contains("/src/test/"))) {
            return "test";
        }
        return "feat";
    }

    /** The superagent-* module with the most changed lines; else the top-level directory with the most; else "repo". */
    static String scope(List<HarnessState.ChangedFile> files) {
        Map<String, Integer> modules = new TreeMap<>();
        Map<String, Integer> dirs = new TreeMap<>();
        for (HarnessState.ChangedFile f : files) {
            String path = newPath(f.path());
            if (path.startsWith(SESSION_DIR)) {
                continue;
            }
            int slash = path.indexOf('/');
            if (slash <= 0) {
                continue; // a file in the repository root
            }
            String top = path.substring(0, slash);
            (top.startsWith(MODULE_PREFIX) ? modules : dirs).merge(top, f.added() + f.removed(), Integer::sum);
        }
        if (!modules.isEmpty()) {
            return most(modules).substring(MODULE_PREFIX.length());
        }
        return dirs.isEmpty() ? "repo" : most(dirs);
    }

    /** Ties go to the name first in order (the maps are sorted), so the result doesn't depend on file order. */
    private static String most(Map<String, Integer> lines) {
        String best = null;
        for (Map.Entry<String, Integer> e : lines.entrySet()) {
            if (best == null || e.getValue() > lines.get(best)) {
                best = e.getKey();
            }
        }
        return best;
    }

    private static List<String> paths(List<HarnessState.ChangedFile> files) {
        return files.stream().map(f -> newPath(f.path())).filter(p -> !p.startsWith(SESSION_DIR)).toList();
    }

    /** A rename in numstat reads "old => new" or "a/{x => y}/b"; the new path is what counts. */
    static String newPath(String path) {
        int arrow = path.indexOf(" => ");
        if (arrow < 0) {
            return path;
        }
        int open = path.lastIndexOf('{', arrow);
        int close = path.indexOf('}', arrow);
        if (open >= 0 && close > arrow) {
            String joined = path.substring(0, open) + path.substring(arrow + 4, close) + path.substring(close + 1);
            return joined.replace("//", "/");
        }
        return path.substring(arrow + 4);
    }
}
