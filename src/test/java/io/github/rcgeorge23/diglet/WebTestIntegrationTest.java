package io.github.rcgeorge23.diglet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.jsoup.nodes.Element;

import static org.assertj.core.api.Assertions.*;

class WebTestIntegrationTest {

    private final List<HttpServer> servers = new ArrayList<>();

    private int startServer(ServerConfig config) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        config.configure(server);
        server.start();
        servers.add(server);
        return server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        for (HttpServer server : servers) {
            server.stop(0);
        }
        servers.clear();
    }

    @FunctionalInterface
    interface ServerConfig {
        void configure(HttpServer server) throws IOException;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void javascriptConsoleErrorFailsByDefault() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><body><script>function triggerError(){console.error('boom');}</script></body></html>")));

        WebTest webTest = new WebTest(port).navigateTo("/");

        assertThatThrownBy(() -> webTest.executeScript("triggerError()"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void javascriptConsoleErrorCanBeIgnoredAndInspected() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><body><script>console.error('boom')</script></body></html>")));

        WebTest webTest = new WebTest(port).ignoreJavascriptErrors().navigateTo("/");

        assertThat(webTest.javascriptErrors()).anyMatch(error -> error.contains("boom"));
    }

    @Test
    void pageScriptErrorFailsByDefault() throws Exception {
        int port = startServer(server ->
                server.createContext("/error", ex -> respond(ex,
                        "<html><body><script>throw new Error('broken script')</script></body></html>")));

        assertThatThrownBy(() -> new WebTest(port).navigateTo("/error"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("broken script");
    }

    @Test
    void clickNavigatesToLinkDestination() throws Exception {
        int port = startServer(server -> {
            server.createContext("/page1", ex -> respond(ex,
                    "<html><body><a id='link' href='/page2'>Next</a></body></html>"));
            server.createContext("/page2", ex -> respond(ex, "<html><body>page2</body></html>"));
        });

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/page1").click("#link").assertPageBodyContains("page2");
    }

    @Test
    void clickHiddenElementNavigates() throws Exception {
        int port = startServer(server -> {
            server.createContext("/page", ex -> respond(ex,
                    "<html><body><a id='hidden' href='/dest' style='display:none'>h</a></body></html>"));
            server.createContext("/dest", ex -> respond(ex, "<html><body>dest</body></html>"));
        });

        new WebTest(port).navigateTo("/page").click("#hidden").assertPageBodyContains("dest");
    }

    @Test
    void evaluateScriptReturnsValue() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><body><script>var x=21*2;</script></body></html>")));

        WebTest webTest = new WebTest(port).navigateTo("/");
        assertThat(webTest.evaluateScript("x").asInt()).isEqualTo(42);
    }

    @Test
    void submitFormPostsValues() throws Exception {
        int port = startServer(server -> {
            server.createContext("/form", ex -> respond(ex,
                    "<html><body><form id='f' action='/submit' method='post'>" +
                            "<input name='q' value='default'/><button>Go</button></form></body></html>"));
            server.createContext("/submit", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(ex, "<html><body>" + body + "</body></html>");
            });
        });

        new WebTest(port)
                .navigateTo("/form")
                .submitForm("f", Map.of("q", "changed"))
                .assertPageBodyContains("q=changed");
    }

    @Test
    void submitFormIncludesCheckedRadioByDefault() throws Exception {
        int port = startServer(server -> {
            server.createContext("/form", ex -> respond(ex,
                    "<html><body><form id='f' action='/submit' method='post'>" +
                            "<input type='radio' name='role' value='STANDARD' checked>" +
                            "<input type='radio' name='role' value='PREMIUM'>" +
                            "<button>Go</button></form></body></html>"));
            server.createContext("/submit", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(ex, "<html><body>" + body + "</body></html>");
            });
        });

        new WebTest(port)
                .navigateTo("/form")
                .submitForm("f", Map.of())
                .assertPageBodyContains("role=STANDARD");
    }

    @Test
    void submitFormUsesGetWhenMethodIsGet() throws Exception {
        int port = startServer(server -> {
            server.createContext("/form", ex -> respond(ex,
                    "<html><body><form id='f' action='/result' method='get'>" +
                            "<input name='q' value='def'/>" +
                            "<button type='submit'>Submit</button>" +
                            "</form></body></html>"));
            server.createContext("/result", ex -> {
                String query = ex.getRequestURI().getRawQuery();
                respond(ex, "<html><body>" + query + "</body></html>");
            });
        });

        new WebTest(port)
                .navigateTo("/form")
                .submitForm("f", Map.of("q", "vv"))
                .assertCurrentUrlIs("http://localhost:" + port + "/result?q=vv")
                .assertPageBodyContains("q=vv");
    }

    @Test
    void submitFormFiresSubmitHandlerAndHonoursPreventDefault() throws Exception {
        int port = startServer(server -> {
            server.createContext("/form", ex -> respond(ex,
                    "<html><body><form id='f' action='/submit' method='post' " +
                            "onsubmit=\"document.getElementById('r').textContent='handled'; return false;\">" +
                            "<input name='q' value='def'/><button type='submit'>Go</button></form>" +
                            "<div id='r'></div></body></html>"));
            server.createContext("/submit", ex -> respond(ex, "<html><body>SUBMITTED</body></html>"));
        });

        WebTest webTest = new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/form")
                .submitForm("f", Map.of("q", "changed"));

        assertThat(webTest.document().getElementById("r").text()).isEqualTo("handled");
        assertThat(webTest.body()).doesNotContain("SUBMITTED");
    }

    @Test
    void submitFormSupportsAjaxSubmission() throws Exception {
        int port = startServer(server -> {
            server.createContext("/form", ex -> respond(ex, """
                    <html><body>
                    <form id='f' action='/submit' method='post'>
                        <input name='q' value='x'/>
                        <button type='submit'>Go</button>
                    </form>
                    <div id='r'></div>
                    <script>
                        document.getElementById('f').addEventListener('submit', e => {
                            e.preventDefault();
                            fetch('/api', { method: 'POST', body: 'q=' + encodeURIComponent(document.querySelector('[name=q]').value) })
                                .then(r => r.text())
                                .then(t => { document.getElementById('r').textContent = t; });
                        });
                    </script>
                    </body></html>
                    """));
            server.createContext("/api", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] bytes = ("api:" + body).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/plain");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            });
        });

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/form")
                .submitForm("f", Map.of("q", "hello"))
                .waitFor(doc -> {
                    Element el = doc.getElementById("r");
                    return el != null && el.text().startsWith("api:");
                })
                .assertPageBodyContains("api:q=hello");
    }

    @Test
    void submitFormHonoursRequiredValidation() throws Exception {
        int port = startServer(server -> {
            server.createContext("/form", ex -> respond(ex,
                    "<html><body><form id='f' action='/submit' method='post'>" +
                            "<input name='q' required/><button type='submit'>Go</button>" +
                            "</form></body></html>"));
            server.createContext("/submit", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(ex, "<html><body>" + body + "</body></html>");
            });
        });

        WebTest webTest = new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/form")
                .submitForm("f", Map.of("q", ""));

        webTest.assertCurrentUrlIs("http://localhost:" + port + "/form");

        webTest.submitForm("f", Map.of("q", "ok"));
        assertThat(webTest.body()).contains("q=ok");
    }

    @Test
    void forceSubmitFormSubmitsEvenWhenConstraintValidationWouldBlock() throws Exception {
        int port = startServer(server -> {
            server.createContext("/form", ex -> respond(ex,
                    "<html><body><form id='f' action='/submit' method='post'>" +
                            "<input name='q' required/><button type='submit'>Go</button>" +
                            "</form></body></html>"));
            server.createContext("/submit", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(ex, "<html><body>" + body + "</body></html>");
            });
        });

        WebTest webTest = new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/form")
                .forceSubmitForm("f", Map.of("q", ""));

        webTest.assertCurrentUrlIs("http://localhost:" + port + "/submit");
        assertThat(webTest.body()).contains("q=");
    }

    @Test
    void forceSubmitFormSkipsJavaScriptSubmitHandlers() throws Exception {
        int port = startServer(server -> {
            server.createContext("/form", ex -> respond(ex,
                    "<html><body><form id='f' action='/submit' method='post' onsubmit=\"document.getElementById('r').textContent='handled';return false;\">" +
                            "<div id='r'>initial</div><input name='q' value='force'/><button type='submit'>Go</button>" +
                            "</form></body></html>"));
            server.createContext("/submit", ex -> respond(ex, "<html><body>SUBMITTED</body></html>"));
        });

        WebTest webTest = new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/form")
                .forceSubmitForm("f", Map.of("q", "force"));

        assertThat(webTest.body()).contains("SUBMITTED");
    }

    @Test
    void submitFormWorksForSubmitButtonInsideHiddenDropdown() throws Exception {
        int port = startServer(server -> {
            server.createContext("/form", ex -> respond(ex,
                    "<html><body><form id='f' action='/submit' method='post'>" +
                            "<input name='q' value='x'/>" +
                            "<ul class='dropdown-menu' style='display:none'><li>" +
                            "<button type='submit'>Go</button></li></ul>" +
                            "</form></body></html>"));
            server.createContext("/submit", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(ex, "<html><body>" + body + "</body></html>");
            });
        });

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/form")
                .submitForm("f", Map.of("q", "hidden"))
                .assertPageBodyContains("q=hidden");
    }

    @Test
    void submitFormSupportsMultipartFileUpload() throws Exception {
        Path file = Files.createTempFile("upload", ".txt");
        Files.writeString(file, "hello");
        file.toFile().deleteOnExit();

        int port = startServer(server -> {
            server.createContext("/form", ex -> respond(ex,
                    "<html><body><form id='f' action='/upload' method='post' enctype='multipart/form-data'>" +
                            "<input type='file' name='file'/><input type='text' name='desc' value='a'/>" +
                            "<button>Upload</button></form></body></html>"));
            server.createContext("/upload", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(ex, "<html><body>" + body + "</body></html>");
            });
        });

        new WebTest(port)
                .navigateTo("/form")
                .submitForm("f", Map.of("desc", "b"), Map.of("file", file))
                .assertPageBodyContains("name=\"desc\"")
                .assertPageBodyContains("b")
                .assertPageBodyContains(file.getFileName().toString())
                .assertPageBodyContains("hello");
    }

    @Test
    void htmlUnitBrowserExecutesJavascript() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><head><script>function go(){document.body.innerHTML='js!';}</script></head><body onload='go()'></body></html>")));

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/")
                .assertPageBodyContains("js!");
    }

    @Test
    void htmlUnitJavascriptCanBeDisabledForServerRenderedPageChecks() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><head><script>document.addEventListener('DOMContentLoaded', () => document.body.textContent='js!');</script></head>"
                        + "<body>server-rendered</body></html>")));

        WebTest webTest = new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .withJavaScriptEnabled(false)
                .navigateTo("/")
                .assertPageBodyContains("server-rendered");

        assertThat(webTest.document().body().text()).isEqualTo("server-rendered");
    }

    @Test
    void followRedirectLoadsDestination() throws Exception {
        int port = startServer(server -> {
            server.createContext("/start", ex -> {
                ex.getResponseHeaders().add("Location", "/end");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            });
            server.createContext("/end", ex -> respond(ex, "<html><body>done</body></html>"));
        });

        new WebTest(port)
                .navigateTo("/start")
                .assertStatusIs(HttpStatus.FOUND)
                .followRedirect()
                .assertPageBodyContains("done");
    }

    @Test
    void automaticallyFollowsRedirectsWhenConfigured() throws Exception {
        int port = startServer(server -> {
            server.createContext("/start", ex -> {
                ex.getResponseHeaders().add("Location", "/end");
                ex.sendResponseHeaders(301, -1);
                ex.close();
            });
            server.createContext("/end", ex -> respond(ex, "<html><body>done</body></html>"));
        });

        new WebTest(port, true)
                .navigateTo("/start")
                .assertStatusIs(HttpStatus.OK)
                .assertPageBodyContains("done");
    }

    @Test
    void postJsonSendsJsonBody() throws Exception {
        int port = startServer(server -> server.createContext("/json", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(ex, "<html><body>" + body + "</body></html>");
        }));

        new WebTest(port)
                .postJson("/json", Map.of("name", "bob", "ids", List.of("one", "two")))
                .assertPageBodyContains("\"name\":\"bob\"")
                .assertPageBodyContains("\"ids\":[\"one\",\"two\"]");
    }

    @Test
    void postFormSendsFormEncodedBody() throws Exception {
        int port = startServer(server -> server.createContext("/formpost", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(ex, "<html><body>" + body + "</body></html>");
        }));

        new WebTest(port)
                .postForm("/formpost", Map.of("a", "1", "b", "2"))
                .assertPageBodyContains("a=1")
                .assertPageBodyContains("b=2");
    }

    @Test
    void fetchPostsJsonBody() throws Exception {
        int port = startServer(server -> {
            server.createContext("/page", ex -> respond(ex, """
                    <html><body>
                    <div id='o'></div>
                    <button id='b' onclick='send()'>Go</button>
                    <script>
                        function send(){fetch('/post',{method:'POST',headers:{"Content-Type":"application/json"},body: JSON.stringify({a:1})}).then(r=>r.text()).then(t=>document.getElementById("o").textContent=t);}
                    </script>
                    </body></html>
                    """));
            server.createContext("/post", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(ex, "<html><body>" + body + "</body></html>");
            });
        });

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/page")
                .click("#b")
                .waitFor(doc -> {
                    Element el = doc.getElementById("o");
                    return el != null && el.text().contains("\"a\":1");
                })
                .assertPageBodyContains("\"a\":1");
    }

    @Test
    void clickExecutesJavascriptAndModifiesDom() throws Exception {
        int port = startServer(server -> server.createContext("/page", ex -> respond(ex,
                "<html><body>" +
                        "<div id='content'></div>" +
                        "<button id='showBtn' onclick='show()'>Show</button>" +
                        "<script>function show(){document.getElementById('content').innerHTML='Hi';}</script>" +
                        "</body></html>")));

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/page")
                .click("#showBtn")
                .assertPageBodyContains("Hi");
    }

    @Test
    void executesExternalScriptsAndLoadsOtherResources() throws Exception {
        int port = startServer(server -> {
            server.createContext("/page", ex -> respond(ex,
                    "<html><body><div id='content'></div><script src='/s.js'></script><img src='/i.png'/></body></html>"));
            server.createContext("/s.js", ex -> {
                byte[] bytes = "document.getElementById('content').innerHTML='loaded';".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/javascript");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.createContext("/i.png", ex -> {
                byte[] bytes = new byte[]{0};
                ex.getResponseHeaders().add("Content-Type", "image/png");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            });
        });

        new WebTest(port)
                .navigateTo("/page")
                .assertStatusIs(HttpStatus.OK)
                .assertPageBodyContains("loaded");
    }

    @Test
    void navigateToBinaryResourceKeepsStatusWithoutParsingTheResponseBody() throws Exception {
        int port = startServer(server -> server.createContext("/image.png", ex -> {
            byte[] bytes = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }));

        WebTest webTest = new WebTest(port).navigateTo("/image.png");

        assertThat(webTest.status()).isEqualTo(HttpStatus.OK.value());
        assertThat(webTest.body()).isEmpty();
        assertThat(webTest.document()).isNotNull();
        assertThat(webTest.document().select("a[href]")).isEmpty();
    }

    @Test
    void missingEmbeddedResourceFailsByDefault() throws Exception {
        int port = startServer(server ->
                server.createContext("/page", ex -> respond(ex,
                        "<html><body><script src='/missing.js'></script></body></html>")));

        assertThatThrownBy(() -> new WebTest(port).navigateTo("/page"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error loading script");
    }

    @Test
    void missingEmbeddedResourceCanBeIgnored() throws Exception {
        int port = startServer(server ->
                server.createContext("/page", ex -> respond(ex,
                        "<html><body><script src='/missing.js'></script></body></html>")));

        new WebTest(port).ignoreJavascriptErrors().navigateTo("/page").assertStatusIs(HttpStatus.OK);
    }

    @Test
    void inputEventUpdatesElement() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><body><input id='t'/><div id='o'></div>" +
                        "<script>document.getElementById('t').addEventListener('input', e => document.getElementById('o').textContent = e.target.value);</script>" +
                        "</body></html>")));

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/")
                .setInputValue("#t", "hi")
                .assertPageBodyContains("hi");
    }

    @Test
    void setInputValueTriggersInputEvent() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><body><input id='t'/><div id='o'></div>" +
                        "<script>document.getElementById('t').addEventListener('input', e => document.getElementById('o').textContent = e.target.value);</script>" +
                        "</body></html>")));

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/")
                .setInputValue("#t", "hi")
                .assertPageBodyContains("hi");
    }

    @Test
    void rendersHtmlUnitPagesWithoutSelfClosingNonVoidElements() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><body><form id='duplicateFieldForm'><input name='title'/>" +
                        "<select data-role='filter' aria-label='Filter items'></select>" +
                        "<textarea name='description'></textarea></form></body></html>")));

        WebTest webTest = new WebTest(port, false, WebTest.Browser.HTML_UNIT).navigateTo("/");

        assertThat(webTest.body()).doesNotContainPattern(
                "<(?!area\\b|base\\b|br\\b|col\\b|embed\\b|hr\\b|img\\b|input\\b|link\\b|meta\\b|param\\b|source\\b|track\\b|wbr\\b)"
                        + "[a-zA-Z][a-zA-Z0-9]*([^>\"']|\"[^\"]*\"|'[^']*')*/>");
        assertThat(webTest.document().select("#duplicateFieldForm textarea[name='description']")).hasSize(1);
        webTest.setInputValue("#duplicateFieldForm textarea[name='description']", "hello");
    }

    @Test
    void waitForConditionOnPage() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><body><div id='o'></div><script>setTimeout(() => {document.getElementById('o').textContent='done';}, 10);</script></body></html>")));

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/")
                .waitFor(doc -> {
                    Element el = doc.getElementById("o");
                    return el != null && "done".equals(el.text());
                })
                .assertPageBodyContains("done");
    }

    @Test
    void changeEventHandlesCheckbox() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><body><input type='checkbox' id='c'/><div id='r'></div>" +
                        "<script>document.getElementById('c').addEventListener('change', e => document.getElementById('r').textContent = e.target.checked ? 'yes' : 'no');</script>" +
                        "</body></html>")));

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/")
                .click("#c")
                .assertPageBodyContains("yes");
    }

    @Test
    void submitFormBySelectorChecksCheckboxWhenOnValueProvided() throws Exception {
        int port = startServer(server -> {
            server.createContext("/form", ex -> respond(ex,
                    "<html><body><form id='f' action='/submit' method='post'>" +
                            "<input type='checkbox' name='marketingConsentGiven' value='on'>" +
                            "<button type='submit'>Save</button></form></body></html>"));
            server.createContext("/submit", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(ex, "<html><body>" + body + "</body></html>");
            });
        });

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/form")
                .submitFormBySelector("#f", Map.of("marketingConsentGiven", "on"))
                .assertPageBodyContains("marketingConsentGiven=on");
    }


    @Test
    void htmlUnitProvidesSymbolPolyfillForLegacyScripts() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><head><script>window.polyfillCheck = typeof Symbol !== 'undefined' && typeof Symbol.iterator !== 'undefined';</script></head>" +
                        "<body><div id='result'></div><script>" +
                        "var iterable = {}; iterable[Symbol.iterator] = function(){ return { next: function(){ return { done: true }; } }; };" +
                        "document.getElementById('result').textContent = window.polyfillCheck ? 'ok' : 'missing';" +
                        "</script></body></html>")));

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/")
                .assertStatusIs(HttpStatus.OK)
                .assertPageBodyContains("ok");
    }

    @Test
    void asynchronousHandlerExecutes() throws Exception {
        int port = startServer(server -> server.createContext("/", ex -> respond(ex,
                "<html><body><button id='b'>Go</button><div id='s'></div>" +
                        "<script>document.getElementById('b').addEventListener('click', () => {setTimeout(() => {document.getElementById('s').textContent='done';}, 10);});</script>" +
                        "</body></html>")));

        new WebTest(port, false, WebTest.Browser.HTML_UNIT)
                .navigateTo("/")
                .click("#b")
                .waitFor(doc -> {
                    Element el = doc.getElementById("s");
                    return el != null && "done".equals(el.text());
                })
                .assertPageBodyContains("done");
    }
}
