package com.meeting.api;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import com.meeting.api.infrastructure.storage.AliyunOssObjectStorageGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end smoke against real Aliyun OSS. Skipped unless the four OSS
 * env vars are present, so CI / dev machines without credentials are unaffected.
 *
 * <p>Run locally:
 * <pre>
 *   export OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
 *   export OSS_REGION=cn-hangzhou
 *   export OSS_ACCESS_KEY_ID=&lt;meeting-api RAM AK&gt;
 *   export OSS_ACCESS_KEY_SECRET=&lt;meeting-api RAM SK&gt;
 *   export STORAGE_BUCKET_AUDIO=meeting-audio-auska   # optional override
 *   ./mvnw -pl meeting-api-start test -Dtest=AliyunOssGatewaySmokeIT
 * </pre>
 *
 * <p>Two smoke flows:
 * <ul>
 *   <li>{@code roundTripsObject} — SDK-side put/stat/presignGet/delete; verifies
 *       SDK wiring, V4 signature config, and that statObject deliberately
 *       returns {@code sha256=null}.
 *   <li>{@code presignPutAcceptsHttpUpload} — exercises the user-facing path:
 *       {@link AliyunOssObjectStorageGateway#presignPut} → HTTP PUT from a
 *       plain {@link HttpClient} → HeadObject + presigned GET. This is the
 *       interaction the frontend actually performs, so SDK-only coverage
 *       would miss endpoint reachability, Content-Type header handling, and
 *       V4-signed PUT correctness.
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "OSS_ENDPOINT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OSS_ACCESS_KEY_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OSS_ACCESS_KEY_SECRET", matches = ".+")
class AliyunOssGatewaySmokeIT {

    @Test
    void roundTripsObject() throws Exception {
        Env env = Env.fromEnvironment();
        String objectKey = "smoke/aliyun-oss-it/" + UUID.randomUUID() + ".bin";
        byte[] payload = ("hello-oss-smoke-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(payload);

        AliyunOssObjectStorageGateway gateway = env.buildGateway();
        try {
            // putObject
            StorageObject put = gateway.putObject(env.bucket, objectKey, payload, "application/octet-stream", sha256);
            assertThat(put.bucket()).isEqualTo(env.bucket);
            assertThat(put.objectKey()).isEqualTo(objectKey);
            assertThat(put.sizeBytes()).isEqualTo(payload.length);

            // statObject — verifies HeadObject + sha256 is intentionally null
            StorageObject stat = gateway.statObject(env.bucket, objectKey);
            assertThat(stat.sizeBytes()).isEqualTo(payload.length);
            assertThat(stat.sha256()).isNull();

            // presignGet + HTTP fetch
            OffsetDateTime exp = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
            ObjectStorageGateway.PresignedUrl signed = gateway.presignGet(env.bucket, objectKey, exp);
            HttpResponse<byte[]> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(
                    HttpRequest.newBuilder(URI.create(signed.url()))
                        .timeout(Duration.ofSeconds(10))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
                );
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(payload);

            // deleteObject + confirm gone via stat
            gateway.deleteObject(env.bucket, objectKey);
            assertThatThrownBy(() -> gateway.statObject(env.bucket, objectKey))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OSS_OBJECT_NOT_FOUND);
        } finally {
            try {
                gateway.deleteObject(env.bucket, objectKey);
            } catch (Exception ignored) {
                // best-effort cleanup; primary delete may have already succeeded
            }
            gateway.shutdown();
        }
    }

    /**
     * Exercises the actual frontend upload path: presigned PUT URL → HTTP
     * PUT (with the gateway-declared headers, notably Content-Type) →
     * HeadObject (via statObject) and presigned GET. If V4 signing or
     * Content-Type wiring breaks, this catches it; the SDK-only smoke
     * above would still pass.
     */
    @Test
    void presignPutAcceptsHttpUpload() throws Exception {
        Env env = Env.fromEnvironment();
        String objectKey = "smoke/aliyun-oss-presign/" + UUID.randomUUID() + ".bin";
        byte[] payload = ("hello-presign-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String contentType = "application/octet-stream";

        AliyunOssObjectStorageGateway gateway = env.buildGateway();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            OffsetDateTime exp = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
            ObjectStorageGateway.PresignedUrl signedPut = gateway.presignPut(
                env.bucket, objectKey, /* partNumber */ 1, contentType, exp
            );
            assertThat(signedPut.headers()).containsEntry("Content-Type", contentType);

            HttpRequest.Builder putBuilder = HttpRequest.newBuilder(URI.create(signedPut.url()))
                .timeout(Duration.ofSeconds(15))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload));
            for (Map.Entry<String, String> header : signedPut.headers().entrySet()) {
                putBuilder.header(header.getKey(), header.getValue());
            }
            HttpResponse<String> putResponse = http.send(
                putBuilder.build(), HttpResponse.BodyHandlers.ofString()
            );
            assertThat(putResponse.statusCode())
                .as("presigned PUT should return 2xx but body was: %s", putResponse.body())
                .isBetween(200, 299);

            // HeadObject via the gateway: validates server actually
            // accepted the body with the expected size.
            StorageObject stat = gateway.statObject(env.bucket, objectKey);
            assertThat(stat.sizeBytes()).isEqualTo(payload.length);
            // ETag for a single-PUT object is the body's MD5 in hex,
            // surrounded by quotes; this confirms the upload landed
            // server-side (we don't compare bytes against the MD5 here
            // because the SDK already validated it).
            assertThat(stat.etag()).isNotBlank();

            // Read back via presigned GET to confirm content integrity.
            ObjectStorageGateway.PresignedUrl signedGet = gateway.presignGet(env.bucket, objectKey, exp);
            HttpResponse<byte[]> getResponse = http.send(
                HttpRequest.newBuilder(URI.create(signedGet.url()))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertThat(getResponse.statusCode()).isEqualTo(200);
            assertThat(getResponse.body()).isEqualTo(payload);
        } finally {
            try {
                gateway.deleteObject(env.bucket, objectKey);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            gateway.shutdown();
        }
    }

    private record Env(String endpoint, String region, String accessKeyId, String accessKeySecret, String bucket) {
        static Env fromEnvironment() {
            return new Env(
                System.getenv("OSS_ENDPOINT"),
                envOrDefault("OSS_REGION", "cn-hangzhou"),
                System.getenv("OSS_ACCESS_KEY_ID"),
                System.getenv("OSS_ACCESS_KEY_SECRET"),
                envOrDefault("STORAGE_BUCKET_AUDIO", "meeting-audio-auska")
            );
        }

        AliyunOssObjectStorageGateway buildGateway() {
            return new AliyunOssObjectStorageGateway(endpoint, region, accessKeyId, accessKeySecret, bucket);
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
