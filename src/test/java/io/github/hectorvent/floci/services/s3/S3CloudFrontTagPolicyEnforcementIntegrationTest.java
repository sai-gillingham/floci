package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/**
 * Verifies CloudFront OAC emulation: when a GetObject request arrives via the CloudFront
 * domain (Host ends with the configured cloudfront domain-suffix), the bucket policy's
 * {@code s3:ExistingObjectTag/visibility = private} Deny is enforced (403), while direct
 * S3 access and public-tagged objects are unaffected.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3CloudFrontTagPolicyEnforcementIntegrationTest {

    private static final String BUCKET = "cf-tag-policy-bucket";

    // Default cloudfront domain-suffix is "cloudfront.net" (EmulatorConfig @WithDefault).
    private static final String CLOUDFRONT_HOST = "e123example.cloudfront.net";

    @Test
    @Order(0)
    void setup() {
        given().when().put("/" + BUCKET).then().statusCode(200);

        String policy = "{"
                + "\"Version\":\"2012-10-17\","
                + "\"Statement\":[{"
                + "\"Sid\":\"DenyCloudFrontPrivateTaggedObjects\","
                + "\"Effect\":\"Deny\","
                + "\"Principal\":{\"Service\":\"cloudfront.amazonaws.com\"},"
                + "\"Action\":\"s3:GetObject\","
                + "\"Resource\":\"arn:aws:s3:::" + BUCKET + "/*\","
                + "\"Condition\":{\"StringEquals\":{\"s3:ExistingObjectTag/visibility\":\"private\"}}"
                + "}]}";
        given().contentType("application/json").body(policy)
                .when().put("/" + BUCKET + "?policy")
                .then().statusCode(anyOf(is(200), is(204)));

        // Private-tagged object
        given().body("secret").when().put("/" + BUCKET + "/private.txt").then().statusCode(200);
        given().contentType("application/xml")
                .body("<Tagging><TagSet><Tag><Key>visibility</Key><Value>private</Value></Tag></TagSet></Tagging>")
                .when().put("/" + BUCKET + "/private.txt?tagging")
                .then().statusCode(anyOf(is(200), is(204)));

        // Public-tagged object
        given().body("hello").when().put("/" + BUCKET + "/public.txt").then().statusCode(200);
        given().contentType("application/xml")
                .body("<Tagging><TagSet><Tag><Key>visibility</Key><Value>public</Value></Tag></TagSet></Tagging>")
                .when().put("/" + BUCKET + "/public.txt?tagging")
                .then().statusCode(anyOf(is(200), is(204)));
    }

    @Test
    @Order(1)
    void cloudFrontHost_privateTaggedObject_isDenied() {
        given().header("Host", CLOUDFRONT_HOST)
                .when().get("/" + BUCKET + "/private.txt")
                .then().statusCode(403);
    }

    @Test
    @Order(2)
    void cloudFrontHost_publicTaggedObject_isServed() {
        given().header("Host", CLOUDFRONT_HOST)
                .when().get("/" + BUCKET + "/public.txt")
                .then().statusCode(200);
    }

    @Test
    @Order(3)
    void directHost_privateTaggedObject_isServed() {
        // Not via the CloudFront domain -> OAC policy not enforced -> object served.
        given().when().get("/" + BUCKET + "/private.txt").then().statusCode(200);
    }

    // --- Allow-only-public form: "Deny unless visibility=public" (StringNotEquals) ---

    private static final String PUBLIC_ONLY_BUCKET = "cf-allow-public-only-bucket";

    @Test
    @Order(4)
    void setupAllowPublicOnly() {
        given().when().put("/" + PUBLIC_ONLY_BUCKET).then().statusCode(200);

        String policy = "{"
                + "\"Version\":\"2012-10-17\","
                + "\"Statement\":[{"
                + "\"Sid\":\"DenyCloudFrontNonPublicObjects\","
                + "\"Effect\":\"Deny\","
                + "\"Principal\":{\"Service\":\"cloudfront.amazonaws.com\"},"
                + "\"Action\":\"s3:GetObject\","
                + "\"Resource\":\"arn:aws:s3:::" + PUBLIC_ONLY_BUCKET + "/*\","
                + "\"Condition\":{\"StringNotEquals\":{\"s3:ExistingObjectTag/visibility\":\"public\"}}"
                + "}]}";
        given().contentType("application/json").body(policy)
                .when().put("/" + PUBLIC_ONLY_BUCKET + "?policy")
                .then().statusCode(anyOf(is(200), is(204)));

        given().body("p").when().put("/" + PUBLIC_ONLY_BUCKET + "/public.txt").then().statusCode(200);
        given().contentType("application/xml")
                .body("<Tagging><TagSet><Tag><Key>visibility</Key><Value>public</Value></Tag></TagSet></Tagging>")
                .when().put("/" + PUBLIC_ONLY_BUCKET + "/public.txt?tagging")
                .then().statusCode(anyOf(is(200), is(204)));

        given().body("x").when().put("/" + PUBLIC_ONLY_BUCKET + "/private.txt").then().statusCode(200);
        given().contentType("application/xml")
                .body("<Tagging><TagSet><Tag><Key>visibility</Key><Value>private</Value></Tag></TagSet></Tagging>")
                .when().put("/" + PUBLIC_ONLY_BUCKET + "/private.txt?tagging")
                .then().statusCode(anyOf(is(200), is(204)));

        // Untagged object (no PutObjectTagging)
        given().body("u").when().put("/" + PUBLIC_ONLY_BUCKET + "/untagged.txt").then().statusCode(200);
    }

    @Test
    @Order(5)
    void allowPublicOnly_publicTagged_isServed() {
        given().header("Host", CLOUDFRONT_HOST)
                .when().get("/" + PUBLIC_ONLY_BUCKET + "/public.txt")
                .then().statusCode(200);
    }

    @Test
    @Order(6)
    void allowPublicOnly_privateTagged_isDenied() {
        given().header("Host", CLOUDFRONT_HOST)
                .when().get("/" + PUBLIC_ONLY_BUCKET + "/private.txt")
                .then().statusCode(403);
    }

    @Test
    @Order(7)
    void allowPublicOnly_untagged_isDenied() {
        given().header("Host", CLOUDFRONT_HOST)
                .when().get("/" + PUBLIC_ONLY_BUCKET + "/untagged.txt")
                .then().statusCode(403);
    }
}
