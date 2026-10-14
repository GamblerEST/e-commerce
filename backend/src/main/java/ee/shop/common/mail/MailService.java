package ee.shop.common.mail;

import ee.shop.common.audit.AuditService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around JavaMailSender.
 *
 * It does NOT throw on a send failure, and that is deliberate:
 *
 *  - Tests run with no real SMTP server (see IntegrationTest) — a throwing
 *    send would fail every test that registers a user or requests a reset.
 *  - In production, a downed SMTP relay must not fail registration or a
 *    password-reset request. The account/token still gets created; the user
 *    can use "resend verification" or try "forgot password" again once mail
 *    is back. Losing an email is recoverable; losing the request that was
 *    ALSO supposed to create the account or the reset token is not.
 *  - For /auth/password/forgot specifically, this also protects the
 *    email-enumeration-safe response: a real account's mail attempt must not
 *    throw and produce a different HTTP response than an unknown email's
 *    no-op, or the difference becomes the oracle.
 *
 * What it no longer does is stay SILENT about it.
 *
 * Until this was fixed, a failure produced one WARN line and nothing else.
 * Every caller — email verification, password reset, order confirmation,
 * payment result, the contact form — carried on as though the message had been
 * delivered, and no durable record existed anywhere. A misconfigured relay
 * meant customers silently never received verification links, and the only
 * evidence was a log line nobody was grepping for. That is the failure mode
 * this class is now built to make visible:
 *
 *  - send() RETURNS whether the message was handed to the mail server, so a
 *    caller that can act on failure (the contact form) is able to.
 *  - A failure is written to the append-only audit log, which is queryable
 *    after the fact — unlike a log line, which is not.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /** Used when a caller has not named the purpose; see the overload below. */
    private static final String UNSPECIFIED = "unspecified";

    private final JavaMailSender sender;
    private final AuditService audit;
    private final String from;

    public MailService(JavaMailSender sender, AuditService audit,
                        @Value("${spring.mail.username}") String from) {
        this.sender = sender;
        this.audit = audit;
        this.from = from;
    }

    public boolean send(String to, String subject, String body) {
        return send(to, subject, body, UNSPECIFIED);
    }

    /**
     * @param purpose short, non-PII label for what this message was for
     *                ("verify-email", "password-reset", "order-confirmation").
     *                It is recorded in the audit log on failure, so it must
     *                never contain the recipient, the subject line, or
     *                anything else a customer supplied.
     * @return true if the mail server accepted the message. False means it did
     *         not — the caller decides whether that is worth telling the user.
     */
    public boolean send(String to, String subject, String body, String purpose) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            return true;
        } catch (MailException e) {
            // WARN, not ERROR: this is an expected, already-handled outcome
            // whenever no real SMTP credentials are configured (e.g. a fresh
            // local environment with a placeholder deploy/secrets/mail_password)
            // — not an operational emergency. Never log the recipient address —
            // email is PII, and PII never reaches a log, not even at DEBUG. The
            // exception message alone is enough to diagnose a genuine SMTP outage.
            log.warn("Failed to send '{}' email: {}", purpose, e.getMessage());

            recordFailure(purpose, e);
            return false;
        }
    }

    /**
     * Same contract as {@link #send(String, String, String, String)} — never
     * throws, returns whether the server accepted it, records a failure in the
     * audit log — with one file attached. Exists only to add the attachment, not
     * to change anything a caller can rely on.
     *
     * The attachment's name and bytes are never logged or audited.
     */
    public boolean sendWithAttachment(String to, String subject, String body, String attachmentFilename,
                                      byte[] attachmentBytes, String attachmentContentType, String purpose) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body);
            helper.addAttachment(attachmentFilename, new ByteArrayResource(attachmentBytes), attachmentContentType);
            sender.send(message);
            return true;
        } catch (MessagingException | MailException e) {
            log.warn("Failed to send '{}' email: {}", purpose, e.getMessage());
            recordFailure(purpose, e);
            return false;
        }
    }

    /**
     * The durable half of the signal. A log line is gone as soon as the
     * container restarts; an audit row can be queried a week later to answer
     * "how long has mail been broken, and how many customers did it affect".
     *
     * Wrapped in its own try/catch because this method exists to report a
     * failure — if the reporting itself fails, that must not turn a
     * recoverable missing email into a failed registration.
     */
    private void recordFailure(String purpose, Exception cause) {
        try {
            // No actor, no IP: this is a system-side outcome, not a user
            // action. The target is the PURPOSE, never the recipient.
            audit.record("mail.send_failed", null,
                    purpose + " (" + cause.getClass().getSimpleName() + ")", null, null);
        } catch (RuntimeException e) {
            log.warn("Could not record a mail failure in the audit log: {}", e.getMessage());
        }
    }
}
