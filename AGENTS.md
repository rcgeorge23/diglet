# Agent instructions

## Project

Diglet (`io.github.rcgeorge23:diglet`) is a lightweight Java library for browser-like tests of server-rendered web applications. It has two run modes:

- `Browser.HTML_UNIT` (default): fast, in-process HtmlUnit engine. Target for the majority of tests.
- `Browser.CHROME` / `Browser.FIREFOX`: Selenium WebDriver, for behaviour HtmlUnit cannot reproduce.

Changing diglet is only valuable if it reduces the need for WebDriver coverage in its downstream consumer while keeping tests fast and lightweight.

## Build and test

- Java 25 toolchain, Gradle wrapper.
- `./gradlew build` - what CI runs (`.github/workflows/ci.yml`).
- `./gradlew test` - full suite (~2.4s for 33 tests); `--offline` works once dependencies are cached.
- Releases are published to Maven Central by `release.yml` on `v*` tags via nmcp.

## Downstream consumer: aucly-micronaut

`rcgeorge23/aucly-micronaut` (private) consumes `io.github.rcgeorge23:diglet` as a `testImplementation` dependency alongside Selenium:

- diglet-based tests: `aucly/src/test/java/uk/co/novinet/aucly/e2e/` (`*WebTest`, `*EndToEndTest`, ...)
- Selenium tests: `*ChromeWebDriverTest` classes in the same package tree, with support classes `ChromeWebDriverTestSupport` / `ParallelChromeWebDriverTestSupport`.
- `TEST_DUPLICATION_REVIEW.md` tracks known duplication between diglet and WebDriver coverage.
- The build separates fast diglet tests from Selenium tasks.

For local evaluation, publish a snapshot with `./gradlew publishToMavenLocal` and consume it from aucly via `mavenLocal()` with the snapshot version.

## Ticket conventions

Issues live in this repo (`rcgeorge23/diglet`). Use the repo issue types (`Bug`, `Feature`, `Task`); do not add labels unless asked. Every issue body should contain `## Summary`, `## Evidence`, `## Proposal`, `## Acceptance criteria`.

### Mandatory: Impact evaluation in aucly

Every ticket's acceptance criteria MUST include an `## Impact evaluation in aucly` section. Diglet exists to reduce WebDriver coverage in aucly-micronaut, so no behaviour change or new capability ships without answering these points in the ticket and again on the issue/PR before release:

1. **Speed** - Expected effect on aucly's test wall clock (its diglet-based tests and full `./gradlew test`). State what to measure before/after.
2. **Reliability** - Expected effect on flakiness, and on false positives/negatives in aucly tests. State which aucly behaviour or test demonstrates the change.
3. **WebDriver redundancy** - Whether the change makes any existing `*ChromeWebDriverTest` redundant. Name the candidate(s) or state "none expected".
4. **Proof** - For each redundant WebDriver test: add/exercise the equivalent diglet test against the new snapshot, show it fails (or is impossible) on the previously released version, and passes with the change. Only then remove the WebDriver counterpart.
5. **Evidence** - Record the measurements and findings on the issue before release. Do not tag a release while the evaluation is outstanding or failing.
