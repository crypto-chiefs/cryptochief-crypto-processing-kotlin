package com.cryptochief.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cryptochief.processing.http.RequestSigner;
import com.cryptochief.processing.webhook.PayoutWebhookEvent;
import com.cryptochief.processing.webhook.WebhookHeadersException;
import com.cryptochief.processing.webhook.WebhookSignatureException;
import com.cryptochief.processing.webhook.WebhookTimestampException;
import com.cryptochief.processing.webhook.WebhookVerificationException;
import com.cryptochief.processing.webhook.WebhookVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import kotlin.jvm.functions.Function1;
import org.junit.jupiter.api.Test;

class WebhookVerifierJavaTest {

    private static final String KEY = "test_api_key_123";
    private static final String DELIVERY = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    @Test
    void verifyFromJava() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String signature = RequestSigner.signWebhookV1(KEY, now, DELIVERY, body);
        Map<String, List<String>> headers = Map.of(
                WebhookVerifier.TIMESTAMP_HEADER, List.of(Long.toString(now)),
                WebhookVerifier.DELIVERY_HEADER, List.of(DELIVERY),
                WebhookVerifier.SIGNATURE_HEADER, List.of(signature));

        WebhookVerifier.verify(KEY, body, headers);
        WebhookVerifier.verify(KEY, body, name -> headers.containsKey(name) ? headers.get(name).get(0) : null);

        assertThrows(WebhookSignatureException.class,
                () -> WebhookVerifier.verify(KEY, "[]".getBytes(StandardCharsets.UTF_8), headers));
        WebhookVerificationException e = assertThrows(WebhookVerificationException.class,
                () -> WebhookVerifier.verify(KEY, body, name -> "Signature".equals(name) ? signature : null));
        assertEquals(WebhookHeadersException.class, e.getClass());
        assertThrows(IllegalArgumentException.class, () -> WebhookVerifier.verify("", body, headers));
    }

    @Test
    void toleranceClockAndParseFromJava() {
        byte[] body = "{\"event\":\"payout.paid\",\"uuid\":\"u-1\",\"order_id\":\"o-1\",\"status\":\"paid\"}"
                .getBytes(StandardCharsets.UTF_8);
        long ts = 1789430400L;
        String signature = RequestSigner.signWebhookV1(KEY, ts, DELIVERY, body);
        Map<String, List<String>> headers = Map.of(
                WebhookVerifier.TIMESTAMP_HEADER, List.of(Long.toString(ts)),
                WebhookVerifier.DELIVERY_HEADER, List.of(DELIVERY),
                WebhookVerifier.SIGNATURE_HEADER, List.of(signature));
        Function1<String, String> header = name -> headers.containsKey(name) ? headers.get(name).get(0) : null;
        Clock later = Clock.fixed(Instant.ofEpochSecond(ts + 600), ZoneOffset.UTC);
        Clock tooLate = Clock.fixed(Instant.ofEpochSecond(ts + 601), ZoneOffset.UTC);
        Duration tolerance = Duration.ofSeconds(600);

        WebhookVerifier.verify(KEY, body, headers, tolerance, later);
        WebhookVerifier.verify(KEY, body, header, tolerance, later);
        assertThrows(WebhookTimestampException.class, () -> WebhookVerifier.verify(KEY, body, headers, tolerance, tooLate));
        assertThrows(WebhookTimestampException.class, () -> WebhookVerifier.verify(KEY, body, header, tolerance, tooLate));

        Clock atDefaultEdge = Clock.fixed(Instant.ofEpochSecond(ts - 300), ZoneOffset.UTC);
        WebhookVerifier.verify(KEY, body, headers, Duration.ZERO, atDefaultEdge);
        assertThrows(WebhookTimestampException.class, () -> WebhookVerifier.verify(KEY, body, headers, Duration.ZERO, later));

        PayoutWebhookEvent fromMap = WebhookVerifier.parse(KEY, body, headers, PayoutWebhookEvent.Companion.serializer(), tolerance, later);
        PayoutWebhookEvent fromFunction = WebhookVerifier.parse(KEY, body, header, PayoutWebhookEvent.Companion.serializer(), tolerance, later);
        assertEquals("u-1", fromMap.getUuid());
        assertEquals("u-1", fromFunction.getUuid());
        assertThrows(WebhookTimestampException.class,
                () -> WebhookVerifier.parse(KEY, body, headers, PayoutWebhookEvent.Companion.serializer()));
        assertThrows(WebhookTimestampException.class,
                () -> WebhookVerifier.parse(KEY, body, header, PayoutWebhookEvent.Companion.serializer()));
    }
}
