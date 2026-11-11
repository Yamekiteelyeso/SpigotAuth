package com.example.spigotauth;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PremiumCheckerTest {

    private PremiumChecker.Result parse(String response, String name) throws Exception {
        return PremiumChecker.parse(
                new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)), name);
    }

    @Test
    void validMatchingMojangProfileIsPremium() throws Exception {
        var profile = parse(
                "{\"name\":\"jeb_\",\"id\":\"853c80ef3c3749fdaa49938b674adae6\"}", "JEB_");
        assertEquals(PremiumChecker.Status.PREMIUM, profile.status());
        assertEquals(UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6"), profile.profileId());
    }

    @Test
    void mismatchedProfileNameIsNeverAccepted() throws Exception {
        var profile = parse(
                "{\"name\":\"SomeoneElse\",\"id\":\"853c80ef3c3749fdaa49938b674adae6\"}", "jeb_");
        assertEquals(PremiumChecker.Status.UNAVAILABLE, profile.status());
    }

    @Test
    void demoProfilesAreNotConsideredPremiumOwners() throws Exception {
        var profile = parse(
                "{\"name\":\"jeb_\",\"id\":\"853c80ef3c3749fdaa49938b674adae6\",\"demo\":true}", "jeb_");
        assertEquals(PremiumChecker.Status.UNAVAILABLE, profile.status());
    }

    @Test
    void incompleteProfileIsNotPremium() throws Exception {
        var profile = parse("{\"name\":\"jeb_\"}", "jeb_");
        assertEquals(PremiumChecker.Status.UNAVAILABLE, profile.status());
    }
}
