package com.studybuddy.flow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.HttpSession;

/**
 * Acts like one person's browser for the full-stack flow tests: it keeps a real session
 * (so login, flash messages and the CSRF token work as they do for a user), follows
 * redirects, and posts forms with the CSRF token taken from the page it is on, the same
 * way the hidden _csrf field reaches the server from a real form.
 * <p>
 * After every request it runs {@code afterEachRequest} (flush and clear the persistence
 * context), so the next page reads fresh from the database as a new request would, even
 * though the whole test runs in one rolled-back transaction.
 */
final class Browser {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");
    private static final int MAX_REDIRECTS = 5;

    private final MockMvc mvc;
    private final Runnable afterEachRequest;
    private final String email;
    private MockHttpSession session = new MockHttpSession();
    private String html = "";
    private String url = "";
    private int status;

    Browser(MockMvc mvc, Runnable afterEachRequest, String email) {
        this.mvc = mvc;
        this.afterEachRequest = afterEachRequest;
        this.email = email;
    }

    /** Types the address into the address bar. */
    Browser open(String address) {
        return send(get(address), 0);
    }

    /**
     * Submits a form on the current page: the action and the fields as name, value pairs.
     * The CSRF token comes from the current page, as the form's hidden field would send it.
     */
    Browser submit(String action, String... fields) {
        MockHttpServletRequestBuilder request = post(action);
        Matcher token = CSRF.matcher(html);
        if (token.find()) {
            request.param("_csrf", token.group(1));
        }
        for (int i = 0; i < fields.length; i += 2) {
            request.param(fields[i], fields[i + 1]);
        }
        return send(request, 0);
    }

    /** Logs in through the real login form (GET /login, then POST it with the page's token). */
    Browser logIn(String loginEmail, String password) {
        open("/login");
        return submit("/login", "email", loginEmail, "password", password);
    }

    Browser logOut() {
        return submit("/logout");
    }

    private Browser send(MockHttpServletRequestBuilder request, int redirects) {
        MvcResult result;
        try {
            result = mvc.perform(request.session(session)).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException("Request failed: " + e.getMessage(), e);
        }
        afterEachRequest.run();
        HttpSession current = result.getRequest().getSession(false);
        // Logging out invalidates the session; the next request then starts a new one, as in a browser
        session = current instanceof MockHttpSession mock && !mock.isInvalid() ? mock : new MockHttpSession();
        status = result.getResponse().getStatus();
        String location = result.getResponse().getRedirectedUrl();
        if (location != null && redirects < MAX_REDIRECTS) {
            return send(get(location.replaceFirst("^http://localhost", "")), redirects + 1);
        }
        url = result.getRequest().getRequestURI()
                + (result.getRequest().getQueryString() == null ? "" : "?" + result.getRequest().getQueryString());
        try {
            html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    /** The page as the person reads it: no tags, entities decoded, whitespace collapsed. */
    String text() {
        String body = html.replaceAll("(?s)<head>.*?</head>", " ").replaceAll("(?s)<!--.*?-->", " ");
        // Inline tags join their text as a browser shows it ("3 students," not "3 students ,"); others separate it
        String plain = body.replaceAll("(?is)</?(span|strong|a|em|b|i)(\\s[^>]*)?>", "")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&mdash;", "—").replace("&larr;", "←").replace("&nbsp;", " ").replace("&amp;", "&");
        return plain.replaceAll("\\s+", " ").trim();
    }

    String html() {
        return html;
    }

    /** The address of the page now shown (after following redirects). */
    String url() {
        return url;
    }

    int status() {
        return status;
    }

    String email() {
        return email;
    }

    /**
     * The first number captured by {@code pattern} in the page source after {@code anchor}, e.g. the id
     * in the "Accept" form next to a student's name. Mirrors clicking the button on that card.
     */
    long idAfter(String anchor, String pattern) {
        int from = html.indexOf(anchor);
        if (from < 0) {
            throw new AssertionError("'" + anchor + "' is not on " + url);
        }
        Matcher matcher = Pattern.compile(pattern).matcher(html);
        if (!matcher.find(from)) {
            throw new AssertionError("No " + pattern + " after '" + anchor + "' on " + url);
        }
        return Long.parseLong(matcher.group(1));
    }

    /** The number captured by {@code pattern} anywhere in the page source, e.g. a link's id next to a name. */
    long id(String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(html);
        if (!matcher.find()) {
            throw new AssertionError("No " + pattern + " on " + url);
        }
        return Long.parseLong(matcher.group(1));
    }

    /** The page source from {@code anchor} to the next {@code end}, e.g. one card. */
    String sectionAfter(String anchor, String end) {
        int from = html.indexOf(anchor);
        if (from < 0) {
            throw new AssertionError("'" + anchor + "' is not on " + url);
        }
        int to = html.indexOf(end, from + anchor.length());
        return html.substring(from, to < 0 ? html.length() : to);
    }
}
