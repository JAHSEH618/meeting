package com.meeting.api.app.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.auth.AuthUserDTO;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class AdminJwtCodec {
    static final String DEFAULT_SECRET = "dev-admin-secret-32-bytes-fixedXX";
    static final String DEFAULT_AUDIENCE = "ai-worker-admin";
    static final String DEFAULT_ISSUER = "meeting-api";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private final String secret;
    private final String audience;
    private final String issuer;

    AdminJwtCodec(String secret, String audience, String issuer) {
        this.secret = nonBlank(secret, DEFAULT_SECRET);
        this.audience = nonBlank(audience, DEFAULT_AUDIENCE);
        this.issuer = nonBlank(issuer, DEFAULT_ISSUER);
    }

    static AdminJwtCodec defaults() {
        return new AdminJwtCodec(DEFAULT_SECRET, DEFAULT_AUDIENCE, DEFAULT_ISSUER);
    }

    String encode(AuthUserDTO user, OffsetDateTime expiresAt) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.userId());
        payload.put("tenantId", user.tenantId());
        payload.put("personId", user.personId());
        payload.put("displayName", user.displayName());
        payload.put("roles", user.roles());
        payload.put("permissions", user.permissions());
        payload.put("aud", audience);
        payload.put("iss", issuer);
        payload.put("exp", expiresAt.toEpochSecond());

        String headerB64 = b64Json(header);
        String payloadB64 = b64Json(payload);
        return headerB64 + "." + payloadB64 + "." + sign(headerB64 + "." + payloadB64);
    }

    Optional<AuthUserDTO> decode(String token, Clock clock) {
        if (token == null || token.chars().filter(ch -> ch == '.').count() != 2) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", 3);
        try {
            JsonNode header = MAPPER.readTree(B64_DEC.decode(parts[0]));
            JsonNode payload = MAPPER.readTree(B64_DEC.decode(parts[1]));
            if (!"HS256".equals(header.path("alg").asText())) {
                return Optional.empty();
            }
            if (!constantTimeEquals(sign(parts[0] + "." + parts[1]), parts[2])) {
                return Optional.empty();
            }
            if (!issuer.equals(payload.path("iss").asText())) {
                return Optional.empty();
            }
            if (!audienceMatches(payload.path("aud"))) {
                return Optional.empty();
            }
            long exp = payload.path("exp").asLong(0);
            if (OffsetDateTime.now(clock).toEpochSecond() >= exp) {
                return Optional.empty();
            }
            String userId = payload.path("sub").asText("");
            String tenantId = payload.path("tenantId").asText("");
            if (userId.isBlank() || tenantId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AuthUserDTO(
                userId,
                tenantId,
                payload.path("personId").asText(null),
                payload.path("displayName").asText(userId),
                textList(payload.path("roles")),
                textList(payload.path("permissions"))
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean audienceMatches(JsonNode audNode) {
        if (audNode.isTextual()) {
            return audience.equals(audNode.asText());
        }
        if (audNode.isArray()) {
            for (JsonNode item : audNode) {
                if (audience.equals(item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isTextual()) {
            out.add(node.asText());
            return out;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    out.add(item.asText());
                }
            }
        }
        return out;
    }

    private static String b64Json(Map<String, Object> value) {
        try {
            return B64_ENC.encodeToString(MAPPER.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize JWT JSON", e);
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return B64_ENC.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign admin JWT", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
