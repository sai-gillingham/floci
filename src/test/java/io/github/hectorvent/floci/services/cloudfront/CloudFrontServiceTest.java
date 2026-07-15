package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.ResourcePolicy;
import io.github.hectorvent.floci.services.cloudfront.model.StreamingDistribution;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class CloudFrontServiceTest {

    private static final String ACCOUNT = "000000000000";

    private CloudFrontService serviceWithDomainSuffix(String domainSuffix) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var cloudFrontConfig = Mockito.mock(EmulatorConfig.CloudFrontServiceConfig.class);

        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudfront()).thenReturn(cloudFrontConfig);
        when(cloudFrontConfig.domainSuffix()).thenReturn(domainSuffix);

        return new CloudFrontService(storageFactory, config);
    }

    @Test
    void createDistributionUsesDefaultDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(".cloudfront.net"),
                "Expected default suffix, got: " + dist.getDomainName());
    }

    @Test
    void createDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.local");

        Distribution dist = service.createDistribution(new Distribution(), Map.of());

        assertTrue(dist.getDomainName().endsWith(".cloudfront.local"),
                "Expected configured suffix, got: " + dist.getDomainName());
    }

    @Test
    void createStreamingDistributionHonorsConfiguredDomainSuffix() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.local");

        StreamingDistribution sd = service.createStreamingDistribution(new StreamingDistribution());

        assertTrue(sd.getDomainName().endsWith(".cloudfront.local"),
                "Expected configured suffix, got: " + sd.getDomainName());
    }

    private static final String VALID_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"cloudfront:GetDistribution","Resource":"*"}]}
            """.strip();

    private static String existingDistributionArn(CloudFrontService service) {
        return service.createDistribution(new Distribution(), Map.of()).getArn();
    }

    @Test
    void resourcePolicyRoundTripsByResourceArn() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        String arn = existingDistributionArn(service);

        ResourcePolicy stored = service.putResourcePolicy(new ResourcePolicy(arn, VALID_POLICY));

        assertEquals(arn, stored.getResourceArn());
        assertEquals(VALID_POLICY, service.getResourcePolicy(arn).getPolicyDocument());
    }

    @Test
    void deleteResourcePolicyRemovesStoredPolicy() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        String arn = existingDistributionArn(service);
        service.putResourcePolicy(new ResourcePolicy(arn, VALID_POLICY));

        service.deleteResourcePolicy(arn);

        AwsException e = assertThrows(AwsException.class, () -> service.getResourcePolicy(arn));
        assertEquals("EntityNotFound", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void putResourcePolicyRejectsNonCloudFrontArn() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");

        AwsException e = assertThrows(AwsException.class, () ->
                service.putResourcePolicy(new ResourcePolicy(
                        "arn:aws:s3:::example-bucket", VALID_POLICY)));

        assertEquals("InvalidArgument", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void putResourcePolicyRejectsMalformedJsonPolicyDocument() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        String arn = existingDistributionArn(service);

        AwsException e = assertThrows(AwsException.class, () ->
                service.putResourcePolicy(new ResourcePolicy(arn, "{not-json}")));

        assertEquals("InvalidArgument", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void putResourcePolicyOnNonexistentResourceThrowsEntityNotFound() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        String arn = "arn:aws:cloudfront::" + ACCOUNT + ":distribution/ENONEXISTENT01";

        AwsException e = assertThrows(AwsException.class, () ->
                service.putResourcePolicy(new ResourcePolicy(arn, VALID_POLICY)));

        assertEquals("EntityNotFound", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void putResourcePolicyRejectsMissingVersion() {
        assertRejectedPolicy(
                "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"cloudfront:GetDistribution\"}]}");
    }

    @Test
    void putResourcePolicyRejectsInvalidVersion() {
        assertRejectedPolicy(
                "{\"Version\":\"2099-01-01\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"cloudfront:GetDistribution\"}]}");
    }

    @Test
    void putResourcePolicyRejectsEmptyStatementArray() {
        assertRejectedPolicy("{\"Version\":\"2012-10-17\",\"Statement\":[]}");
    }

    @Test
    void putResourcePolicyRejectsInvalidEffect() {
        assertRejectedPolicy(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Permit\",\"Principal\":\"*\",\"Action\":\"cloudfront:GetDistribution\"}]}");
    }

    @Test
    void putResourcePolicyRejectsStatementMissingPrincipal() {
        assertRejectedPolicy(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"cloudfront:GetDistribution\"}]}");
    }

    @Test
    void putResourcePolicyRejectsStatementMissingAction() {
        assertRejectedPolicy(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\"}]}");
    }

    @Test
    void putResourcePolicyAcceptsSingleStatementObjectAndPrincipalMap() {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        String arn = existingDistributionArn(service);
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":"
                + "{\"Sid\":\"Cross\",\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":[\"arn:aws:iam::111122223333:root\"]},"
                + "\"Action\":[\"cloudfront:GetDistribution\"],\"Resource\":\"*\"}}";

        ResourcePolicy stored = service.putResourcePolicy(new ResourcePolicy(arn, document));

        assertEquals(document, service.getResourcePolicy(stored.getResourceArn()).getPolicyDocument());
    }

    private void assertRejectedPolicy(String document) {
        CloudFrontService service = serviceWithDomainSuffix("cloudfront.net");
        String arn = existingDistributionArn(service);

        AwsException e = assertThrows(AwsException.class, () ->
                service.putResourcePolicy(new ResourcePolicy(arn, document)));

        assertEquals("InvalidArgument", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }
}
