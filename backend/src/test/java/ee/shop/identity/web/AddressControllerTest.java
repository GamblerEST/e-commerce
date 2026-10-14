package ee.shop.identity.web;

import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.RegisterRequest;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The saved-address book: the account page manages addresses.
 *
 * The cases that matter are the ownership boundary and the single-default
 * invariant — the two things that would be quietly wrong rather than obviously
 * broken.
 */
@Tag("api")
@Tag("security")
@AutoConfigureMockMvc
class AddressControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String tokenFor(String who) throws Exception {
        var req = new RegisterRequest(who + "-" + UUID.randomUUID() + "@test.ee",
                "correct-horse-battery", "Address Tester", null);
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(res.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    private String body(String label, boolean makeDefault) {
        return """
                {"label":"%s","recipientName":"Kadri Tamm","phone":"+372 5555 1234",
                 "line1":"Pikk tn 1","city":"Tallinn","postalCode":"10123","country":"EE",
                 "makeDefault":%s}
                """.formatted(label, makeDefault);
    }

    private String create(String token, String label, boolean makeDefault) throws Exception {
        MvcResult res = mvc.perform(post("/api/account/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(label, makeDefault)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void addressesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/account/addresses")).andExpect(status().isUnauthorized());
    }

    @Test
    void theFirstAddressBecomesTheDefaultEvenIfNotAsked() throws Exception {
        String token = tokenFor("first");
        // Otherwise a customer has addresses and checkout has nothing to
        // preselect, which is indistinguishable from having none.
        create(token, "Home", false);

        mvc.perform(get("/api/account/addresses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].isDefault").value(true));
    }

    @Test
    void makingOneDefaultClearsThePrevious() throws Exception {
        String token = tokenFor("default");
        create(token, "Home", true);
        String office = create(token, "Office", true);

        // The database enforces one default per user with a partial unique
        // index; this proves the code clears the old one rather than relying
        // on being lucky about ordering.
        String listed = mvc.perform(get("/api/account/addresses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var arr = json.readTree(listed);
        long defaults = 0;
        for (var node : arr) if (node.get("isDefault").asBoolean()) defaults++;
        assertThat(defaults).isEqualTo(1);
        assertThat(arr.get(0).get("id").asText()).isEqualTo(office);
    }

    @Test
    void deletingTheDefaultPromotesAnother() throws Exception {
        String token = tokenFor("promote");
        String home = create(token, "Home", true);
        create(token, "Office", false);

        mvc.perform(delete("/api/account/addresses/" + home)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // A customer left with addresses but no default would see nothing
        // preselected at checkout for no visible reason.
        mvc.perform(get("/api/account/addresses").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].isDefault").value(true));
    }

    // ── the ownership boundary ───────────────────────────────────────────

    @Test
    void anotherCustomersAddressIsNotFound_notForbidden() throws Exception {
        String mine = tokenFor("mine");
        String theirs = tokenFor("theirs");
        String theirAddress = create(theirs, "Their home", true);

        // 404, not 403: confirming that an id exists but belongs to someone
        // else is itself a small leak.
        mvc.perform(get("/api/account/addresses").header("Authorization", "Bearer " + mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mvc.perform(delete("/api/account/addresses/" + theirAddress)
                        .header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/account/addresses/" + theirAddress)
                        .header("Authorization", "Bearer " + mine)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Hijacked", false)))
                .andExpect(status().isNotFound());
    }

    @Test
    void addressesAreEncryptedAtRest() throws Exception {
        String token = tokenFor("crypto");
        create(token, "Home", true);

        // Same treatment as the billing snapshot on an order — an address book
        // is the same category of data, and protecting one but not the other
        // would be arbitrary.
        String stored = jdbc.queryForObject(
                "SELECT line1 FROM user_addresses ORDER BY created_at DESC LIMIT 1", String.class);
        assertThat(stored).startsWith("enc:v1:").doesNotContain("Pikk tn 1");
    }

    @Test
    void invalidAddressesAreRejectedPerField() throws Exception {
        String token = tokenFor("invalid");
        mvc.perform(post("/api/account/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"","line1":"","city":"","postalCode":"",
                                 "country":"eesti","makeDefault":false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recipientName").isNotEmpty())
                .andExpect(jsonPath("$.errors.country").isNotEmpty());
    }

    // ── addresses picked from the Estonian address register ─────────────

    private String registerBody(String line1, String postalCode, String adsOid) {
        return """
                {"recipientName":"Kadri Tamm","line1":"%s","city":"Tallinn",
                 "postalCode":"%s","country":"EE","makeDefault":false,"adsOid":"%s"}
                """.formatted(line1, postalCode, adsOid);
    }

    @Test
    void anAddressPickedFromTheRegisterIsSaved() throws Exception {
        String token = tokenFor("register-ok");

        // Lai tn 2 shares its register id with Pikk tn 1 on the same corner building.
        mvc.perform(post("/api/account/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Lai tn 2", "10133", "EE00658009")))
                .andExpect(status().isCreated());
    }

    /**
     * The attack: keep a genuine register id and change the house number, so a
     * made-up address borrows the credibility of a verified one.
     */
    @Test
    void aTamperedAddressClaimingARegisterIdIsRefused() throws Exception {
        String token = tokenFor("register-tamper");

        mvc.perform(post("/api/account/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Pikk tn 12", "10123", "EE00658009")))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.errors.line1").exists());

        // Checked through the API, not SQL: saved addresses are encrypted at rest,
        // so a query on the line1 column could never match, and the assertion
        // would pass whatever the endpoint had done.
        mvc.perform(get("/api/account/addresses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$").isEmpty());
    }

    @Test
    void aMalformedRegisterIdIsRejectedByValidation() throws Exception {
        String token = tokenFor("register-malformed");

        mvc.perform(post("/api/account/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Pikk tn 1", "10123", "../etc/passwd")))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.errors.adsOid").exists());
    }
}
