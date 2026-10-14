package ee.shop.support.web;

import ee.shop.common.mail.MailService;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public contact form.
 *
 * MailService is mocked so the tests can assert what would be SENT — the real
 * one deliberately swallows failures, so a live call would pass whether or not
 * the message was ever composed correctly.
 */
@Tag("api")
@AutoConfigureMockMvc
class ContactControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean MailService mail;

    @org.junit.jupiter.api.BeforeEach
    void mailSucceedsByDefault() {
        // The mock returns false for un-stubbed boolean methods, which would
        // make every test here look like an SMTP outage.
        when(mail.send(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    private String body(String name, String email, String subject, String message) {
        return """
                {"name":"%s","email":"%s","subject":"%s","message":"%s"}
                """.formatted(name, email, subject, message);
    }

    private static final String VALID_MESSAGE =
            "I have a question about my recent order and when it will arrive.";

    /**
     * A lost contact message leaves no other trace — no order, no token, no row
     * to recover it from. So unlike every other mail in the application, this
     * caller must NOT tell the sender "we have your message" when the mail
     * server refused it.
     */
    @Test
    void whenMailCannotBeSent_theSenderIsToldRatherThanReassured() throws Exception {
        when(mail.send(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        mvc.perform(post("/api/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Kadri", "kadri@example.ee", "Urgent", VALID_MESSAGE)))
                .andExpect(status().isServiceUnavailable())
                // And is given a way through that does not depend on us.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("email us directly")));
    }

    @Test
    void aValidMessage_isAccepted_andEmailed() throws Exception {
        mvc.perform(post("/api/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Kadri Tamm", "kadri@example.ee", "Order question", VALID_MESSAGE)))
                // 202, not 200: MailService swallows delivery failures, so the
                // honest claim is "accepted", never "delivered".
                .andExpect(status().isAccepted());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce())
                .send(anyString(), subject.capture(), text.capture(), anyString());

        assertThat(subject.getValue()).contains("Order question");
        // The sender's own address must appear in the BODY so the shop can
        // reply — see the header-injection test below for why not the header.
        assertThat(text.getValue()).contains("kadri@example.ee").contains("Kadri Tamm");
    }

    /**
     * The subject line is a mail HEADER. A newline in caller-controlled input
     * that reaches a header lets the sender append headers of their own — a
     * Bcc: to recipients of their choosing, for instance. The newline must not
     * survive into the subject.
     */
    @Test
    void newlinesInTheSubject_cannotInjectMailHeaders() throws Exception {
        mvc.perform(post("/api/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Attacker", "attacker@example.ee",
                                "Hello\\r\\nBcc: victim@example.ee", VALID_MESSAGE)))
                .andExpect(status().isAccepted());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce())
                .send(anyString(), subject.capture(), anyString(), anyString());

        assertThat(subject.getValue())
                .doesNotContain("\r")
                .doesNotContain("\n");
    }

    // ── validation ───────────────────────────────────────────────────────

    @Test
    void anEmptyMessage_isRejectedWithFieldErrors() throws Exception {
        mvc.perform(post("/api/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "not-an-email", "", "short")))
                .andExpect(status().isBadRequest())
                // Named per field, so the form can highlight the right input
                // rather than showing one generic failure.
                .andExpect(jsonPath("$.errors.name").isNotEmpty())
                .andExpect(jsonPath("$.errors.email").isNotEmpty())
                .andExpect(jsonPath("$.errors.subject").isNotEmpty())
                .andExpect(jsonPath("$.errors.message").isNotEmpty());
    }

    @Test
    void anOverlongMessage_isRejected() throws Exception {
        // Unauthenticated and unbounded would invite a multi-megabyte body.
        mvc.perform(post("/api/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Kadri", "kadri@example.ee", "Subject", "x".repeat(4001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.message").isNotEmpty());
    }

    @Test
    void theFormIsPublic_noAuthenticationRequired() throws Exception {
        // The people most likely to need this form are the ones who cannot log
        // in, so requiring a session would defeat its purpose.
        mvc.perform(post("/api/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Guest", "guest@example.ee", "Hello", VALID_MESSAGE)))
                .andExpect(status().isAccepted());
    }
}
