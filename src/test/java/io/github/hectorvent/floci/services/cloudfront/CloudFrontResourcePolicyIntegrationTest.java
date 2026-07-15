package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFrontResourcePolicyIntegrationTest {

    private static final String XML = "application/xml";
    private static final String NS = "http://cloudfront.amazonaws.com/doc/2020-05-31/";
    private static final Pattern ARN_PATTERN = Pattern.compile("<ARN>(.*?)</ARN>");

    @Test
    void putGetAndDeleteResourcePolicyThroughRestXml() {
        String arn = createDistributionAndReturnArn();
        String policyDocument = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"cloudfront:GetDistribution","Resource":"*"}]}
                """.strip();

        given()
                .contentType(XML)
                .body(resourcePolicyRequest("PutResourcePolicyRequest", arn, policyDocument))
        .when()
                .post("/2020-05-31/put-resource-policy")
        .then()
                .statusCode(200)
                .body(containsString("<PutResourcePolicyResult"))
                .body(containsString("<ResourceArn>" + arn + "</ResourceArn>"));

        given()
                .contentType(XML)
                .body(resourceArnRequest("GetResourcePolicyRequest", arn))
        .when()
                .post("/2020-05-31/get-resource-policy")
        .then()
                .statusCode(200)
                .body(containsString("<GetResourcePolicyResult"))
                .body(containsString("<ResourceArn>" + arn + "</ResourceArn>"))
                .body(containsString("<PolicyDocument>" + XmlBuilder.escape(policyDocument)
                        + "</PolicyDocument>"));

        given()
                .contentType(XML)
                .body(resourceArnRequest("DeleteResourcePolicyRequest", arn))
        .when()
                .post("/2020-05-31/delete-resource-policy")
        .then()
                .statusCode(200);

        given()
                .contentType(XML)
                .body(resourceArnRequest("GetResourcePolicyRequest", arn))
        .when()
                .post("/2020-05-31/get-resource-policy")
        .then()
                .statusCode(404)
                .body(containsString("<Code>EntityNotFound</Code>"));
    }

    @Test
    void putResourcePolicyRejectsMalformedPolicyDocumentXml() {
        String arn = createDistributionAndReturnArn();

        given()
                .contentType(XML)
                .body(resourcePolicyRequest("PutResourcePolicyRequest", arn, "{not-json}"))
        .when()
                .post("/2020-05-31/put-resource-policy")
        .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidArgument</Code>"));
    }

    @Test
    void putResourcePolicyRejectsPolicyMissingRequiredElements() {
        String arn = createDistributionAndReturnArn();
        // Valid JSON object, but not a valid resource policy (no Version/Statement).
        given()
                .contentType(XML)
                .body(resourcePolicyRequest("PutResourcePolicyRequest", arn, "{\"foo\":\"bar\"}"))
        .when()
                .post("/2020-05-31/put-resource-policy")
        .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidArgument</Code>"));
    }

    @Test
    void putResourcePolicyOnNonexistentResourceReturnsEntityNotFound() {
        String arn = "arn:aws:cloudfront::000000000000:distribution/ENOTREAL0000001";
        String policyDocument = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"cloudfront:GetDistribution"}]}
                """.strip();

        given()
                .contentType(XML)
                .body(resourcePolicyRequest("PutResourcePolicyRequest", arn, policyDocument))
        .when()
                .post("/2020-05-31/put-resource-policy")
        .then()
                .statusCode(404)
                .body(containsString("<Code>EntityNotFound</Code>"));
    }

    private static String createDistributionAndReturnArn() {
        String config = new XmlBuilder()
                .start("DistributionConfig", NS)
                .elem("CallerReference", UUID.randomUUID().toString())
                .elem("Enabled", "false")
                .elem("Comment", "resource-policy-it")
                .end("DistributionConfig")
                .build();

        String responseBody = given()
                .contentType(XML)
                .body(config)
        .when()
                .post("/2020-05-31/distribution")
        .then()
                .statusCode(201)
                .extract().body().asString();

        Matcher matcher = ARN_PATTERN.matcher(responseBody);
        assertTrue(matcher.find(), "Expected an <ARN> in the create-distribution response");
        return matcher.group(1);
    }

    private static String resourcePolicyRequest(String root, String arn, String policyDocument) {
        return new XmlBuilder()
                .start(root, NS)
                .elem("ResourceArn", arn)
                .elem("PolicyDocument", policyDocument)
                .end(root)
                .build();
    }

    private static String resourceArnRequest(String root, String arn) {
        return new XmlBuilder()
                .start(root, NS)
                .elem("ResourceArn", arn)
                .end(root)
                .build();
    }
}
