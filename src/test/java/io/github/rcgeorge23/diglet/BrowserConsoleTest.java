package io.github.rcgeorge23.diglet;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BrowserConsoleTest {

    @Test
    void recordsLogsAndErrorsAndClears() {
        BrowserConsole console = new BrowserConsole();

        console.getLogs().add("hello");
        console.getErrors().add("boom");

        assertThat(console.getLogs()).containsExactly("hello");
        assertThat(console.getErrors()).containsExactly("boom");

        console.clear();
        assertThat(console.getLogs()).isEmpty();
        assertThat(console.getErrors()).isEmpty();
    }

    @Test
    void ignoresHashedBootstrapBundleScriptErrors() {
        assertThat(WebTest.shouldIgnoreBootstrapScriptError(
                "identifier is a reserved word: class (http://localhost:41333/js/bootstrap.bundle.min-b75ae000439862b6a97d2129c85680e8.js#6)"
        )).isTrue();
    }

    @Test
    void doesNotIgnoreOtherScriptErrors() {
        assertThat(WebTest.shouldIgnoreBootstrapScriptError(
                "ReferenceError: foo is not defined (http://localhost/js/app.js#1)"
        )).isFalse();
    }

    @Test
    void allowsErrorsToBeReadWhileTheBrowserRecordsAnotherError() throws InterruptedException {
        BrowserConsole console = new BrowserConsole();
        console.getErrors().add("existing");

        CountDownLatch iterationStarted = new CountDownLatch(1);
        CountDownLatch recorderFinished = new CountDownLatch(1);
        Thread recorder = new Thread(() -> {
            try {
                assertThat(iterationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                console.getErrors().add("concurrent");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                recorderFinished.countDown();
            }
        });

        recorder.start();
        assertThatCode(() -> console.getErrors().forEach(error -> {
            iterationStarted.countDown();
            try {
                assertThat(recorderFinished.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        })).doesNotThrowAnyException();
        recorder.join(5_000);

        assertThat(console.getErrors()).contains("concurrent");
    }

}
