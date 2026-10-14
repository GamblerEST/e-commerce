package ee.shop.common.mail;

import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * A failed email must not be silent.
 *
 * MailService deliberately does not throw — a downed relay must not fail a
 * registration or a checkout. But it previously did nothing else either: one
 * WARN line, no return value, no record. A misconfigured relay meant customers
 * never received verification links and the only evidence was a log nobody was
 * grepping. These tests pin the two things that make it visible instead.
 */
@Tag("api")
class MailServiceTest extends IntegrationTest {

    @Autowired MailService mail;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender sender;

    private int failureRowsFor(String purpose) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'mail.send_failed' AND target_id LIKE ?",
                Integer.class, purpose + "%");
        return n == null ? 0 : n;
    }

    @Test
    void aSuccessfulSendReportsTrue() {
        doNothing().when(sender).send(any(SimpleMailMessage.class));

        assertThat(mail.send("someone@example.ee", "Subject", "Body", "verify-email")).isTrue();
    }

    @Test
    void aFailedSendReportsFalseRatherThanThrowing() {
        doThrow(new MailSendException("relay is down"))
                .when(sender).send(any(SimpleMailMessage.class));

        // Not throwing is the deliberate part: the caller's own work (creating
        // the account, the order, the reset token) must still commit.
        assertThat(mail.send("someone@example.ee", "Subject", "Body", "verify-email")).isFalse();
    }

    @Test
    void aFailedSendIsRecordedInTheAppendOnlyAuditLog() {
        doThrow(new MailSendException("relay is down"))
                .when(sender).send(any(SimpleMailMessage.class));

        int before = failureRowsFor("password-reset");
        mail.send("someone@example.ee", "Reset your password", "Body", "password-reset");

        // The durable half. A log line is gone on the next container restart;
        // this row answers "how long has mail been broken" a week later.
        assertThat(failureRowsFor("password-reset")).isEqualTo(before + 1);
    }

    @Test
    void theAuditRecordNeverContainsTheRecipient() {
        doThrow(new MailSendException("relay is down"))
                .when(sender).send(any(SimpleMailMessage.class));

        String recipient = "very.identifiable.person@example.ee";
        mail.send(recipient, "Verify your email", "Body", "verify-email");

        // Email is PII, and the rule is that PII never reaches a log or
        // an audit row — including the ones written when something goes wrong,
        // which is exactly when it is most tempting to include it.
        String targets = String.join(" ", jdbc.queryForList(
                "SELECT target_id FROM audit_log WHERE action = 'mail.send_failed'", String.class));
        assertThat(targets).doesNotContain(recipient).doesNotContain("example.ee");
    }
}
