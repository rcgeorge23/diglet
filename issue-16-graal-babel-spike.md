# Issue #16: GraalJS-hosted Babel spike

**Decision: no-go for a Diglet HtmlUnit Babel mode in its current form.** The
prototype can parse and execute both selected Aucly bundles, but transforming
real application bundles causes repeatable Aucly behaviour regressions and
large cold costs. Keep Diglet's production artifact free of GraalVM and Babel.
If modern JavaScript support becomes a product priority, evaluate a separate
browser process such as Lightpanda before revisiting in a new ticket.

This is an evidence report and a reproducible prototype recipe, not production
code. The executable experiment lived outside the repository at
`/tmp/opencode/diglet-issue16-spike`; the temporary Graal adapter lived in the
detached worktree `/tmp/opencode/diglet-issue16-eval`. Neither belongs in a
Diglet release. The Lightpanda executable and pre-existing files under
`/tmp/opencode/lightpanda-spike` were not modified.

## Reproduce the Babel/HtmlUnit bundle probe

Environment used: Corretto JDK 25.0.3, Gradle 9.7.1, HtmlUnit 4.21.0, Graal
Polyglot/JS 25.0.2, Babel standalone 7.29.9 (`@babel/standalone` npm tarball).
Install Babel standalone into a local directory, then use these dependencies
in a throwaway Java project (the diglet wrapper was not changed for this
experiment):

```groovy
dependencies {
    implementation 'org.htmlunit:htmlunit:4.21.0'
    implementation 'org.graalvm.polyglot:polyglot:25.0.2'
    runtimeOnly 'org.graalvm.polyglot:js:25.0.2'
}
```

The essential host-side transform is:

```java
Context context = Context.newBuilder("js").allowAllAccess(false).build();
context.eval(Source.newBuilder("js", Files.readString(babelPath), "babel.min.js").build());
Value babel = context.getBindings("js").getMember("Babel");
Value options = context.eval("js", "({sourceType:'unambiguous',comments:false,compact:'auto',"
        + "presets:[['env',{targets:{ie:'11'},modules:false,useBuiltIns:false}]]})");
String output = babel.invokeMember("transform", input, options).getMember("code").asString();
```

The HtmlUnit probe serves the real assets from Aucly's
`aucly/src/main/resources/public`, rewrites the actual
`views/organiser/collect_payments_v3.html` module tag to a classic script only
for this self-contained bundle, and intercepts JavaScript responses through
`WebConnectionWrapper`. It drops stale `Content-Length`/`Content-Encoding`
headers after replacing response bodies. The sample uses a SHA-256 in-memory
cache around Babel output. For the collect-payments fixture, initialize
`window.__INITIAL_STATE__` with a representative state and
`pollIntervalMs: 0`; assert that
`.collect-payments-v3-auction-title` renders `WINTER GALA`. For the second
bundle, load `admin-auction-completion-workflow.js`, dispatch
`show.bs.modal`, answer its details `fetch` with two recipients, and assert
`Reminder: 2 emails queued.` plus two list items.

From the prototype directory, the measured direct transform/execution command
was:

```sh
./gradlew run --args="/path/to/babel.min.js /path/to/aucly/aucly/src/main/resources/public"
```

Use `--execute-only` as the third argument to skip the four direct transform
samples and run the HtmlUnit response-rewrite probe. The complete temporary
source also includes the local HTTP fixture, response wrapper, cache, error
listener, and assertions. The Java dependencies and snippets above describe the
host/transform seam without adding that spike code to Diglet.

## Per-bundle transform and execution results

The Aucly module is the actual 263,805-byte minified Vue 3 bundle
`/js/v3/collect-payments.js`; it has no static `import`/`export` declarations.
It contains optional chaining, nullish coalescing, logical assignment,
classes, and async/await. The second asset is the actual 1,961-byte
`/js/admin-auction-completion-workflow.js`, which uses optional chaining and
async/await.

One Graal context took **390 ms** to start; loading Babel standalone took
**4,061 ms**. With that context, Babel targeting IE11 produced:

| Asset | Input → output | First transform | Later transforms in same context |
|---|---:|---:|---:|
| `collect-payments.js` | 263,805 → 474,530 bytes | 79,827 ms | 78,766 / 76,224 / 76,634 ms |
| `admin-auction-completion-workflow.js` | 1,961 → 7,215 bytes | 677 ms* | 700 / 689 / 743 ms |

\*The admin asset was transformed after the large Vue bundle had warmed the
same Graal engine. A separate response-path run measured the first collect
transform at **80,603 ms** and the admin transform at **1,081 ms**. Both
transformed assets executed in HtmlUnit with no captured JavaScript errors:
the Vue page rendered `WINTER GALA`; the admin workflow rendered
`Reminder: 2 emails queued.` and two recipients.

Fetching the same collect bundle again in the same test JVM hit the
SHA-256 cache and recorded **0 ms** at millisecond precision. A fresh process
with `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI` still reported the
interpreter fallback because Libgraal/the Graal compiler was unavailable; its
first collect transform was **82,635 ms**, same-JVM cache hit **0 ms**, and
admin transform **952 ms**. Thus the warm cache helps repeated identical
responses within one JVM, but it does not make a first transformation of a
large bundle practical. Babel does not polyfill web APIs, and removing
`type="module"` is valid here only because the Aucly Vue bundle has no static
module imports/exports; this is not general ES module linking support.

## Aucly suite impact

The experimental response adapter was gated behind a system property and used
a synchronized, per-test-JVM Graal context and SHA-256 content cache. Aucly
configures one test worker JVM (`maxParallelForks = 1`, `forkEvery = 0`) while
running test classes concurrently. A metrics file recorded **13,562** JavaScript
response transformations across **67** URL paths: **86 cache misses** and
**13,476 cache hits**. Miss transforms took **593,169 ms (9m53s)** in aggregate
over 1,624,460 input bytes and 2,607,932 output bytes; hit durations rounded to
0 ms. Major miss costs were Chart UMD versions (217.4s total), jQuery (91.8s),
Bootstrap (73.9s), organiser dashboard (39.1s), auction (28.6s), and organiser
forms (25.3s). The cache was process-local, not persistent between Gradle test
JVMs.

The HtmlUnit candidate
`AdminPagesEndToEndTest.completionWorkflowShowsLazyLoadedEmailDetailsTriggerForCompletedEmailSteps`
passed with the adapter enabled in **1m28s** test time (1m56s E2E task
reporter, 2m25s Gradle wall time). This is an actual use of the transformed
admin bundle.

The full Aucly suite on the release baseline (Diglet 0.1.7) reported 3,710
tests, **15m14s** test-task time / **16m59s** wall, with no retries. With the
experimental artifact and Babel adapter, it ran the same 3,710 tests: the
initial test task took **19m19s**, then the retry task **5m39s**, for **26m37s**
total; three tests remained failed. Treat the overall timing delta cautiously:
this is a single comparison and the Aucly working tree had later test-only
assertion/dialog edits. The 9m53s of cold transforms and the paired controls
below are the direct cost/behavior evidence; cache hits do not incur another
transform.

Two failures were controlled against the same Aucly tests and are attributable
to the transformed runtime behavior. Both pass on published Diglet 0.1.7, then
fail on the experimental artifact with Babel enabled:

| Aucly test | Release 0.1.7 | Babel experiment | Observed failure |
|---|---|---|---|
| `BidderViewAuctionEndToEndTest.winnerCanSeePaymentStatusAndConfirmPaymentFromItemPage` | pass, 7.2s | fail, 1m45s | payment-confirm modal was not visible at the assertion |
| `BidderViewAuctionEndToEndTest.cancellingPlaceBidModalRemovesBackdropOnItemDetailPage` | pass, 3.3s | fail, 7.3s | modal backdrop did not disappear within 5s |

Both were reproduced in the full experimental run and the focused controlled
run. A third full-suite failure,
`PublicPagesIntegrationTest.organiserSeesCreateAuctionPromptWhenNoAuctions`,
also persisted on retry, but it is a server-rendered assertion and was not
isolated; do not attribute it to Babel. The module is not currently exercised
by an Aucly `*WebTest`; V3 route coverage is real Chrome/WebDriver coverage. The
async admin bundle is exercised by the HtmlUnit candidate above.

## Lightpanda comparison

Used the existing local binary, Lightpanda
`1.0.0-nightly.9774+a720a5198`, in CDP `serve` mode and the same real Aucly
bundle sources and initial state. A separate fixture served the actual
Thymeleaf fragment after substituting its initial state and resolving its
module URL. The admin fixture served the same completion-workflow JS and
two-recipient JSON response. Puppeteer connected to the local CDP endpoint.
Two runs reported:

| Probe | Run 1 | Run 2 | Behavior / errors |
|---|---:|---:|---|
| Lightpanda process → CDP ready | 103 ms | 82 ms | — |
| Collect-payments navigation and title render | 141 ms | 130 ms | `WINTER GALA`; no page/console errors |
| Admin completion workflow | 39 ms | 35 ms | expected summary + two recipients; no page/console errors |

Lightpanda executes these assets natively through V8; no source rewriting or
transpile cache is required. This is not a drop-in Diglet backend: it introduces
a browser process/CDP integration and has its own feature gaps (the run logged
iframes disabled, irrelevant to these fixtures). The local `puppeteer-core`
package declares Node >=22.12 while this probe ran on Node 18.19.1; the import
and run succeeded, but that is not a supported-version guarantee.

**Recommendation:** no-go on the GraalJS/Babel option. It failed two controlled
Aucly behavior checks and imposed multi-second Babel startup plus roughly
80-second cold transforms for the target Vue bundle. Lightpanda successfully
ran the same Aucly behaviors quickly and with native modern JavaScript, so it
is the stronger direction for a future browser-process experiment if its
deployment model and broader feature coverage can be validated. No Diglet
production API, Graal dependency, Babel artifact, or spike implementation was
added by this evaluation; any future adoption needs a separate scoped issue.

## Primary references

- Babel standalone: <https://babeljs.io/docs/babel-standalone>
- Babel preset-env: <https://babeljs.io/docs/babel-preset-env>
- GraalJS embedding: <https://www.graalvm.org/javascript/docs/>
- Lightpanda browser: <https://github.com/lightpanda-io/browser>
- Aucly collect-payments template and bundle: `aucly/src/main/resources/views/organiser/collect_payments_v3.html`, `aucly/src/main/resources/public/js/v3/collect-payments.js`
- Aucly async admin bundle: `aucly/src/main/resources/public/js/admin-auction-completion-workflow.js`
