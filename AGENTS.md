# AGENTS.md

Guidance for AI coding agents working in this repository. The canonical, detailed version lives in [CLAUDE.md](CLAUDE.md); this file mirrors the essentials for tools that read `AGENTS.md`.

> **Never send anything out to a remote.** No `git push`, no `scp` / `rsync` / `ssh`
> uploads, no deploying to or running commands on the deploy host. Pulling in is fine (`git pull`, `git fetch`,
> `git clone`), as is local git (`status`, `diff`, `log`, `add`, `commit`). Shipping out is done by the user.

## What this is

SuperAgent is an AI collaborative work system for companies, not a personal assistant: everyone works with AI in one system, and the work becomes company assets (see [README.md](README.md)). Design from the company's and its employees' side. It iterates fast; you will often be adding new functionality as well as changing existing behavior.

## Build & test

Maven multi-module, **Java 21**, Spring Boot 3.5.3. No Maven wrapper — use a local `mvn`.

```bash
mvn -T 1C clean install                       # build + test all modules (modules in parallel)
mvn -T 1C clean install -DskipTests           # build, skip tests
mvn -pl superagent-task -am test              # one module (+ its deps)
mvn -pl superagent-core test -Dtest=FooTest   # single test class
scripts/run-local.sh                          # run everything on :18080 from its own jar copy (mvn package, not install)
```

Tests run in parallel (set in the parent pom): don't share process-wide state between tests (unique in-memory DB
names and temp paths, port 0); a test asserting on JVM-wide state is `@Isolated` or takes a shared `@ResourceLock`.

## Module architecture

Layered; depend only downward (full table and conventions in [CLAUDE.md](CLAUDE.md)):

- **L0** superagent-core (domain records, store interfaces, error bases), superagent-api (DTOs)
- **L1** superagent-storage (JDBC), superagent-runtime (agent CLIs, MCP grants, attachments)
- **L2** superagent-platform (users, settings, providers, connectors), superagent-skills (skills), superagent-project
  (projects), superagent-files (work files and their storage)
- **L3** superagent-agent (chat, memory, collaboration), superagent-task (tasks, automations) — siblings, never depend on
  each other; superagent-docs (Feishu / DingTalk docs), superagent-media (speech to text), superagent-cost (model tiers)
- **L4** superagent-orchestrator (SuperAgent), superagent-event (inbox)
- **L5** superagent-web (controllers, MCP endpoint, auth), superagent-ui (static UI)
- **L6** superagent-server (boots it all)

Packages follow modules (`com.superagent.<module>…`); business logic stays out of web; each module contributes
its own MCP tools via `McpToolProvider`. The server `@ComponentScan("com.superagent")`s everything.

Put code in the module it belongs to by responsibility and layer — not wherever is nearest or open. If no
existing module fits, create a new one at the right layer (register it in the parent `pom.xml`, depend only
downward, keep packages under `com.superagent.*`) rather than bending an ill-fitting module. Aim for a sensible
distribution of code across modules.

Everything belongs to one company (multi-tenant): new tables carry `company_id` and every query filters by
`TenantContext.require()` (fail-closed); background and cross-thread work runs inside `TenantContext.run(companyId, …)`.
Settings are per company — don't add new global settings (only CLI install, price catalog and the built-in MCP address are
platform-wide).

Errors: domain exceptions extend the `com.superagent.core.error` bases; let them propagate — don't catch-and-log at the
throw site. `GlobalExceptionHandler` (or, for streamed work, the stream's terminal error event) handles them centrally.

## Deployment

`scripts/diff_build.sh <commit-id> [-m <msg>] [-o <dir>]` packages what changed since that commit for someone to apply
by hand; nothing leaves the machine. It commits uncommitted changes locally first (never any remote operation), then
writes `superagent-<from>-<to>.tar.gz` holding `changes.tar.gz` and an `apply.sh` run from the target's root. It prints
`<to>`, the commit to pass next time. Updating a host is done by the user.
