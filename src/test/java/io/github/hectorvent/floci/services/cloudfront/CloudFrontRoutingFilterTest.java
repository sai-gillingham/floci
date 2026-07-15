package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFrontRoutingFilterTest {

    // --- extractBucketFromOriginDomain ---

    @ParameterizedTest
    @CsvSource({
            "bucket.s3.us-east-1.amazonaws.com,        bucket",
            "bucket.s3.amazonaws.com,                  bucket",
            "bucket.s3.localhost.localstack.cloud,     bucket",
            "bucket.s3.localhost.floci.io,             bucket",
            "bucket.s3.us-east-1.localhost:4566,       bucket",
            "bucket.s3-website-us-east-1.amazonaws.com, bucket",
            "bucket.s3-website.us-east-1.amazonaws.com, bucket",
            "my.dotted.bucket.s3.amazonaws.com,        my.dotted.bucket",
            "assets.s3.eu-west-2.amazonaws.com,        assets",
    })
    void extractsBucketFromS3OriginDomain(String domain, String expected) {
        assertEquals(expected, CloudFrontRoutingFilter.extractBucketFromOriginDomain(domain));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "example.com",                       // custom (non-S3) origin
            "api.internal.example.org",          // custom origin
            "s3.amazonaws.com",                  // path-style, no bucket label
    })
    void returnsNullForNonS3OrPathStyleOrigin(String domain) {
        assertNull(CloudFrontRoutingFilter.extractBucketFromOriginDomain(domain));
    }

    // --- buildOriginPath ---

    @Test
    void buildsPathStyleWithoutOriginPath() {
        assertEquals("/my-bucket/images/logo.png",
                CloudFrontRoutingFilter.buildOriginPath("my-bucket", null, "/images/logo.png", null));
    }

    @Test
    void buildsPathStyleWithOriginPath() {
        assertEquals("/my-bucket/static/images/logo.png",
                CloudFrontRoutingFilter.buildOriginPath("my-bucket", "/static", "/images/logo.png", null));
    }

    @Test
    void normalizesOriginPathSlashes() {
        assertEquals("/my-bucket/static/images/logo.png",
                CloudFrontRoutingFilter.buildOriginPath("my-bucket", "static/", "/images/logo.png", null));
    }

    @Test
    void appliesDefaultRootObjectForRootRequest() {
        assertEquals("/my-bucket/index.html",
                CloudFrontRoutingFilter.buildOriginPath("my-bucket", null, "/", "index.html"));
    }

    @Test
    void rootRequestWithoutDefaultRootObjectStaysRoot() {
        assertEquals("/my-bucket/",
                CloudFrontRoutingFilter.buildOriginPath("my-bucket", null, "/", null));
    }

    // --- globMatches ---

    @ParameterizedTest
    @CsvSource({
            "images/*,     images/logo.png, true",
            "/images/*,    images/logo.png, true",
            "*.jpg,        photos/a.jpg,    true",
            "*,            anything/here,   true",
            "images/*,     videos/clip.mp4, false",
            "*.jpg,        photos/a.png,    false",
    })
    void matchesPathPatterns(String pattern, String path, boolean expected) {
        assertEquals(expected, CloudFrontRoutingFilter.globMatches(pattern, path));
    }

    // --- selectOrigin ---

    @Test
    void selectsDefaultOriginWhenNoCacheBehaviorMatches() {
        Origin s3 = origin("s3-origin", "bucket.s3.amazonaws.com");
        DistributionConfig cfg = new DistributionConfig();
        cfg.setOrigins(List.of(s3));
        DefaultCacheBehavior dcb = new DefaultCacheBehavior();
        dcb.setTargetOriginId("s3-origin");
        cfg.setDefaultCacheBehavior(dcb);

        assertEquals(s3, CloudFrontRoutingFilter.selectOrigin(cfg, "/images/x.png"));
    }

    @Test
    void selectsCacheBehaviorOriginWhenPatternMatches() {
        Origin defaultOrigin = origin("default", "default-bucket.s3.amazonaws.com");
        Origin imagesOrigin = origin("images", "images-bucket.s3.amazonaws.com");
        DistributionConfig cfg = new DistributionConfig();
        cfg.setOrigins(List.of(defaultOrigin, imagesOrigin));
        DefaultCacheBehavior dcb = new DefaultCacheBehavior();
        dcb.setTargetOriginId("default");
        cfg.setDefaultCacheBehavior(dcb);
        CacheBehavior cb = new CacheBehavior();
        cb.setPathPattern("images/*");
        cb.setTargetOriginId("images");
        cfg.setCacheBehaviors(List.of(cb));

        assertEquals(imagesOrigin, CloudFrontRoutingFilter.selectOrigin(cfg, "/images/x.png"));
        assertEquals(defaultOrigin, CloudFrontRoutingFilter.selectOrigin(cfg, "/other/x.png"));
    }

    @Test
    void fallsBackToFirstOriginWhenTargetUnresolved() {
        Origin first = origin("first", "first-bucket.s3.amazonaws.com");
        DistributionConfig cfg = new DistributionConfig();
        cfg.setOrigins(List.of(first));

        assertEquals(first, CloudFrontRoutingFilter.selectOrigin(cfg, "/images/x.png"));
    }

    @Test
    void returnsNullWhenNoOrigins() {
        DistributionConfig cfg = new DistributionConfig();
        assertNull(CloudFrontRoutingFilter.selectOrigin(cfg, "/images/x.png"));
    }

    // --- end-to-end path composition (regression for the reported bug) ---

    @Test
    void routesCdnRequestToBucketWithoutBucketInViewerUrl() {
        // Viewer requests d123.cloudfront.net/images/foo/x.png (no bucket in URL).
        Origin s3 = origin("s3-origin", "assets-bucket.s3.us-east-1.amazonaws.com");
        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setOrigins(List.of(s3));
        DefaultCacheBehavior dcb = new DefaultCacheBehavior();
        dcb.setTargetOriginId("s3-origin");
        cfg.setDefaultCacheBehavior(dcb);

        Origin selected = CloudFrontRoutingFilter.selectOrigin(cfg, "/images/foo/x.png");
        String bucket = CloudFrontRoutingFilter.extractBucketFromOriginDomain(selected.getDomainName());
        String rewritten = CloudFrontRoutingFilter.buildOriginPath(
                bucket, selected.getOriginPath(), "/images/foo/x.png", cfg.getDefaultRootObject());

        assertEquals("assets-bucket", bucket);
        assertEquals("/assets-bucket/images/foo/x.png", rewritten);
        assertTrue(rewritten.startsWith("/" + bucket + "/"));
        assertFalse(rewritten.contains("//"));
    }

    private static Origin origin(String id, String domainName) {
        Origin o = new Origin();
        o.setId(id);
        o.setDomainName(domainName);
        return o;
    }
}
