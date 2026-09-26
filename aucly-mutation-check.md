# Aucly Diglet E2E mutation check

This is an opt-in confidence check for the Diglet-based Aucly end-to-end tests. It is not wired
into `./gradlew test` or `./gradlew build`. It temporarily changes one Aucly production
JavaScript, template, or controller behavior, runs the paired test, and restores the source.

## Setup

Use Java 25 and an Aucly checkout. In the Diglet checkout, publish the code under test to
MavenLocal (use the version being evaluated):

```sh
./gradlew publishToMavenLocal -Pversion=0.1.8-SNAPSHOT
```

Aucly declares project-level repositories, so add MavenLocal and force the chosen Diglet version
with a temporary Gradle init script such as `/tmp/diglet-mavenlocal.init.gradle`:

```groovy
def selectedDigletVersion = gradle.startParameter.projectProperties['digletVersion'] ?: '0.1.8-SNAPSHOT'

settingsEvaluated { settings ->
    settings.dependencyResolutionManagement.repositories.mavenLocal()
}

gradle.projectsEvaluated {
    allprojects { project ->
        project.repositories.mavenLocal()
        project.configurations.configureEach { configuration ->
            configuration.resolutionStrategy.eachDependency { details ->
                if (details.requested.group == 'io.github.rcgeorge23'
                        && details.requested.name == 'diglet') {
                    details.useVersion(selectedDigletVersion)
                }
            }
        }
        if (project.findProperty('rerunEndToEnd') == 'true') {
            project.tasks.matching { it.name == 'endToEndTests' }.configureEach {
                outputs.upToDateWhen { false }
            }
        }
    }
}
```

From the Aucly checkout, confirm the dependency resolves to that snapshot before running tests:

```sh
./gradlew -I /tmp/diglet-mavenlocal.init.gradle \
  -PdigletVersion=0.1.8-SNAPSHOT \
  :aucly:dependencyInsight --dependency io.github.rcgeorge23:diglet \
  --configuration testRuntimeClasspath
```

## Run safely

1. Confirm the five production files below have no pre-existing diffs. Preserve unrelated test
   changes. Save exact copies of all five files outside the Aucly checkout before mutating them and
   register restoration of all five copies in a shell `trap`. Do not use `git checkout` to restore
   files because that could discard unrelated work.
2. Establish an unmutated baseline with the five test filters below in one invocation. Each test
   must pass.
3. Apply all five listed mutations together, then run all five paired tests in one invocation. This
   keeps the opt-in check short while each mutation remains isolated to its own test surface.
4. Inspect the result for each paired test. Count a mutant as killed only if its named test executed
   and failed for the expected behavioral assertion; compilation/setup failures are invalid runs. A
   passing paired test is a surviving mutant and must be triaged by strengthening the test or filing
   a follow-up issue. Restore all five files from the saved copies in the trap, then verify all five
   production files are clean and run `git diff --check`.

Run each filter from the Aucly root with the required profiles and snapshot init script:

```sh
SPRING_PROFILES_ACTIVE=codex,mockgoogle,mockstripe ./gradlew \
  -I /tmp/diglet-mavenlocal.init.gradle \
  -PdigletVersion=0.1.8-SNAPSHOT -PrerunEndToEnd=true \
  :aucly:endToEndTests \
  --tests 'uk.co.novinet.aucly.e2e.organiser.RequestOrganiserRoleWebTest.bidderBecomesOrganiserThroughRequestForm' \
  --tests 'uk.co.novinet.aucly.e2e.admin.AdminPagesEndToEndTest.adminCanRemoveQueuedEmailFromEmailQueuePage' \
  --tests 'uk.co.novinet.aucly.e2e.organiser.HelpGuidesEndToEndTest.organiserEmailTemplatesPageTracksUnsavedChangesBeforeUnload' \
  --tests 'uk.co.novinet.aucly.e2e.organiser.HelpGuidesEndToEndTest.legacyFaqRoutesRedirectToHelpGuides' \
  --tests 'uk.co.novinet.aucly.e2e.organiser.HelpGuidesEndToEndTest.organiserCanAccessHelpGuides'
```

Apply all five mutations from the table before this command. Each test is paired with exactly one
mutated surface; verify that each failure matches its row’s expected signature. The five-test
unmutated baseline passed all five in 1m12s wall time (54.9s test-task time). These checks remain
opt-in and do not add work to Aucly or Diglet’s normal test tasks.

## Mutation cases and observed results

All five Aucly production mutations were restored and verified absent from `git diff`. Results
were obtained with MavenLocal Diglet `0.1.8-SNAPSHOT` and the profiles/init script above.

| Surface | Temporary production mutation | Paired Aucly Diglet test | Observed result |
| --- | --- | --- | --- |
| Fetch/XHR validation | In `aucly/src/main/resources/public/js/organiser-path.js`, change `if (data.available)` to `if (!data.available)`. | `RequestOrganiserRoleWebTest.bidderBecomesOrganiserThroughRequestForm` | **Killed.** The test timed out waiting for the taken-path `fa-times` icon (5s wait; 13.5s test, 1m26s Gradle wall). |
| Form submission | In `aucly/src/main/resources/views/admin/email_queue.html`, change the queued-email removal button from `type="submit"` to `type="button"`, leaving its `confirm()` handler intact. | `AdminPagesEndToEndTest.adminCanRemoveQueuedEmailFromEmailQueuePage` | **Killed.** The click no longer submitted the form; the test failed waiting for the 302/redirect (5s condition; 11.9s test, 1m15s wall). |
| DOM mutation | In `aucly/src/main/resources/public/js/organiser-email-templates.js`, replace `root.dataset.hasUnsavedChanges = hasUnsavedChanges(root) ? 'true' : 'false';` with `root.dataset.hasUnsavedChanges = 'false';`. | `HelpGuidesEndToEndTest.organiserEmailTemplatesPageTracksUnsavedChangesBeforeUnload` | **Killed.** The test expected `"true"` and observed `"false"` (7.3s test, 53s wall). |
| Navigation/redirect | In `HelpController.legacyFaq()`, change `redirect:/help` to `redirect:/__mutation`; leave `legacyOrganiserFaq()` unchanged. | `HelpGuidesEndToEndTest.legacyFaqRoutesRedirectToHelpGuides` | **Killed.** The test expected `/help` and received `/__mutation` (2.7s test, 1m1s wall). |
| Server-rendered content | In `aucly/src/main/resources/views/help/index.html`, change the section heading `Organiser guides` to `Organiser resources`. | `HelpGuidesEndToEndTest.organiserCanAccessHelpGuides` | **Initially survived** because a hero link elsewhere also says `Organiser guides`. Strengthened the test to assert exactly one `#organiser-guides h2.h4` and its exact text; the unmutated test passed, and the mutant was then **killed** (`Organiser resources` observed instead of `Organiser guides`; 10.3s test, 53s wall). |

Final result after triage: **5/5 mutants killed (100%)**. The server-rendered survivor exposed an Aucly assertion weakness, not a Diglet defect; the assertion now targets the intended section heading. No WebDriver test was removed. No product code or normal build/test task was changed by this runbook.

### Batched verification run

The five mutations were also applied together and checked in one `endToEndTests` invocation with the five filters above. All five test methods executed and failed for their paired behavioral reason; there were no setup or compilation failures. The task took 51.9s and the Gradle invocation took 1m10s (4 tasks executed, 6 up-to-date). Combined with the unmutated baseline, the opt-in check took about 2m22s.

| Paired test | Batched-run failure signature |
| --- | --- |
| `RequestOrganiserRoleWebTest.bidderBecomesOrganiserThroughRequestForm` | With availability inverted, the taken path did not show `fa-times`; its 5s wait timed out. |
| `AdminPagesEndToEndTest.adminCanRemoveQueuedEmailFromEmailQueuePage` | With the button changed to `type="button"`, submission did not occur and its 5s redirect condition timed out. |
| `HelpGuidesEndToEndTest.organiserEmailTemplatesPageTracksUnsavedChangesBeforeUnload` | With the dirty-state flag forced false, expected `true` but observed `false`. |
| `HelpGuidesEndToEndTest.legacyFaqRoutesRedirectToHelpGuides` | With `/faq` redirected to `/__mutation`, expected `/help` but observed `/__mutation`. |
| `HelpGuidesEndToEndTest.organiserCanAccessHelpGuides` | With the section heading changed, expected `Organiser guides` but observed `Organiser resources`. |
