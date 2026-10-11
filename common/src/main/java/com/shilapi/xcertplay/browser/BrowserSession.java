package com.shilapi.xcertplay.browser;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Automatic same-origin session; access is intentionally open to the local network. */
final class BrowserSession {
    private final String token;
    BrowserSession() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    String cookie() { return "bridge=" + token + "; Path=/; HttpOnly; Secure; SameSite=Strict"; }
    boolean authorized(String cookie) {
        if (cookie == null) return false;
        for (String part : cookie.split(";")) {
            if (part.trim().startsWith("bridge="))
                return MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), part.trim().substring(7).getBytes(StandardCharsets.US_ASCII));
        }
        return false;
    }
}
