package ee.shop.support.web;

import ee.shop.common.error.AppException;
import ee.shop.common.mail.MailService;
import ee.shop.common.security.RateLimitService;
import ee.shop.support.web.dto.ContactRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public contact form.
 *
 * Messages are emailed to the shop rather than stored in a table. That is a
 * deliberate scope choice: a support inbox already exists and is where someone
 * actually reads and replies, whereas a `contact_messages` table would need its
 * own admin screen, its own read/unread state and its own retention policy
 * before it was any more useful than an email.
 *
 * Two consequences of being unauthenticated and public:
 *
 *  - It is rate limited per IP, on the same token-bucket machinery as
 *    registration. Without that, this endpoint is a free open mail relay
 *    pointed at the shop's own inbox.
 *  - The reply-to address is caller-supplied and therefore NOT trusted. It is
 *    placed in the BODY of the notification, never in a mail header, because a
 *    header built from unvalidated input is a header-injection hole (a newline
 *    in the value lets the sender add Bcc: recipients of their choosing).
 */
@RestController
@Tag(name = "Support")
public class ContactController {

    private final MailService mail;
    private final RateLimitService rateLimit;
    private final String supportInbox;

    public ContactController(MailService mail, RateLimitService rateLimit,
                              @Value("${app.support-email:${spring.mail.username}}") String supportInbox) {
        this.mail = mail;
        this.rateLimit = rateLimit;
        this.supportInbox = supportInbox;
    }

    @PostMapping("/api/contact")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send a message to the shop from the public contact form")
    public void send(@Valid @RequestBody ContactRequest req, HttpServletRequest http) {
        rateLimit.enforceContact(http.getRemoteAddr());

        // Newlines are stripped from anything that reaches the subject line.
        // The subject IS a mail header, so a raw newline here would let a
        // sender append headers of their own.
        String subject = "[Contact] " + req.subject().replaceAll("[\\r\\n]", " ");

        String body = """
                A message was sent from the website contact form.

                From:  %s
                Email: %s

                %s
                """.formatted(req.name(), req.email(), req.message());

        // Unlike every other mail in this application, a lost contact message
        // leaves NO other trace — there is no order, no token, no row to
        // recover it from, and the sender has been told it arrived. So this is
        // the one caller that acts on the result rather than shrugging.
        if (!mail.send(supportInbox, subject, body, "contact-form")) {
            throw AppException.unavailable(
                    "We could not send your message just now. Please email us directly instead.");
        }

        // 202, not 200: accepted by the mail server is not the same as
        // delivered to a human, and only the first of those is knowable here.
    }
}
