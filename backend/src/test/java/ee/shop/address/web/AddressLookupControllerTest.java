package ee.shop.address.web;

import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The address search endpoint, against the local register stand-in that every
 * integration test is wired to (see IntegrationTest).
 */
@Tag("api")
@Tag("security")
@AutoConfigureMockMvc
class AddressLookupControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;

    /**
     * Public on purpose: a guest checking out has no account, and the security
     * configuration denies by default, so this proves the allowlist entry
     * actually matches the path.
     */
    @Test
    void searchIsPublicAndReturnsFormReadyFields() throws Exception {
        mvc.perform(get("/api/addresses/search").param("q", "Pikk 1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].line1").value("Pikk tn 1"))
                .andExpect(jsonPath("$[0].city").value("Tallinn"))
                .andExpect(jsonPath("$[0].postalCode").value("10123"))
                .andExpect(jsonPath("$[0].country").value("EE"))
                .andExpect(jsonPath("$[0].adsOid").value("EE00658009"));
    }

    @Test
    void aShortQueryAnswersEmptyWithoutCallingTheRegister() throws Exception {
        GAZETTEER.clearQueries();

        mvc.perform(get("/api/addresses/search").param("q", "Pi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        assertThat(GAZETTEER.rawQueries()).isEmpty();
    }

    /** Free-text GET parameters get no automatic length limit; this one has an explicit one. */
    @Test
    void anOverlongQueryIsRejectedBeforeReachingTheRegister() throws Exception {
        GAZETTEER.clearQueries();

        mvc.perform(get("/api/addresses/search").param("q", "a".repeat(121)))
                .andExpect(status().isBadRequest());

        assertThat(GAZETTEER.rawQueries()).isEmpty();
    }
}
