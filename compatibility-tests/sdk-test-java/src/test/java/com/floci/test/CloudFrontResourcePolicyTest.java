package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudFrontResourcePolicyTest {

    private static CloudFrontClient cloudFront;

    @BeforeAll
    static void setup() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(),
                "This test creates a throwaway distribution and attaches a resource policy to it.");
        cloudFront = TestFixtures.cloudFrontClient();
    }

    @AfterAll
    static void cleanup() {
        if (cloudFront != null) {
            cloudFront.close();
        }
    }

    @Test
    void resourcePolicyLifecycleWorksThroughAwsSdk() {
        String arn = createDistribution();
        String document = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"cloudfront:GetDistribution","Resource":"*"}]}
                """.strip();

        var put = cloudFront.putResourcePolicy(b -> b
                .resourceArn(arn)
                .policyDocument(document));
        assertThat(put.resourceArn()).isEqualTo(arn);

        var get = cloudFront.getResourcePolicy(b -> b.resourceArn(arn));
        assertThat(get.resourceArn()).isEqualTo(arn);
        assertThat(get.policyDocument()).isEqualTo(document);

        cloudFront.deleteResourcePolicy(b -> b.resourceArn(arn));

        assertThatThrownBy(() -> cloudFront.getResourcePolicy(b -> b.resourceArn(arn)))
                .isInstanceOfSatisfying(CloudFrontException.class, e ->
                        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("EntityNotFound"));
    }

    @Test
    void putResourcePolicyOnNonexistentResourceThrowsEntityNotFound() {
        String distributionId = "E" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 13)
                .toUpperCase();
        String arn = "arn:aws:cloudfront::000000000000:distribution/" + distributionId;
        String document = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"cloudfront:GetDistribution"}]}
                """.strip();

        assertThatThrownBy(() -> cloudFront.putResourcePolicy(b -> b
                .resourceArn(arn)
                .policyDocument(document)))
                .isInstanceOfSatisfying(CloudFrontException.class, e ->
                        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("EntityNotFound"));
    }

    @Test
    void putResourcePolicyRejectsPolicyDocumentThatIsNotAValidPolicy() {
        String arn = createDistribution();

        assertThatThrownBy(() -> cloudFront.putResourcePolicy(b -> b
                .resourceArn(arn)
                .policyDocument("{\"not\":\"a-policy\"}")))
                .isInstanceOfSatisfying(CloudFrontException.class, e ->
                        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidArgument"));
    }

    private static String createDistribution() {
        var response = cloudFront.createDistribution(b -> b
                .distributionConfig(c -> c
                        .callerReference(UUID.randomUUID().toString())
                        .enabled(false)
                        .comment("resource-policy-compat")));
        return response.distribution().arn();
    }
}
