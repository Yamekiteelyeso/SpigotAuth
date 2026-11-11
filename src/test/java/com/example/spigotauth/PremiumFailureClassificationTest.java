package com.example.spigotauth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class PremiumFailureClassificationTest {

    @Test
    void recognizesNetworkFailuresEvenWhenWrapped() {
        assertTrue(Paper1201SmartAuthAdapter.isNetworkOrTimeoutFailure(
                new SecurityException("Session service timed out",
                        new RuntimeException(new TimeoutException()))));
        assertTrue(Paper1201SmartAuthAdapter.isNetworkOrTimeoutFailure(
                new IOException("connection reset")));
        assertTrue(Paper1201SmartAuthAdapter.isNetworkOrTimeoutFailure(
                new SecurityException("transport",
                        new ConnectException("connection refused"))));
    }

    @Test
    void keepsInvalidSessionsSeparateFromOutages() {
        assertFalse(Paper1201SmartAuthAdapter.isNetworkOrTimeoutFailure(
                new SecurityException("invalid verify token")));
        assertFalse(Paper1201SmartAuthAdapter.isNetworkOrTimeoutFailure(
                new IllegalArgumentException("invalid state")));
    }
}
