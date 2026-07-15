package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Routes CloudFront viewer requests to the distribution's S3 origin.
 *
 * <p>When a request arrives with a Host header matching a distribution's domain
 * name ({@code <id>.<domainSuffix>}) or one of its aliases, the request path is
 * rewritten to the path-style S3 form {@code /<bucket>/<originPath>/<key>} so the
 * S3 controller can serve the object. This mirrors real CloudFront, where the
 * origin bucket is resolved from the distribution configuration and never appears
 * in the viewer URL — for example {@code d123.cloudfront.net/images/x.png} serves
 * the {@code images/x.png} key from the origin bucket.
 *
 * <p>Runs before {@link io.github.hectorvent.floci.services.s3.S3VirtualHostFilter}.
 * That filter ignores CloudFront hosts (their remainder does not match an S3
 * endpoint), so the two never both rewrite the same request.
 */
@Provider
@PreMatching
@Priority(4)
@ApplicationScoped
public class CloudFrontRoutingFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(CloudFrontRoutingFilter.class);

    private final CloudFrontService service;

    @Inject
    public CloudFrontRoutingFilter(CloudFrontService service) {
        this.service = service;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String host = requestContext.getHeaderString("Host");
        if (host == null || host.isBlank()) {
            return;
        }

        String hostname = stripPort(host);
        Optional<Distribution> distOpt = service.findDistributionByDomain(hostname);
        if (distOpt.isEmpty()) {
            return;
        }

        DistributionConfig cfg = distOpt.get().getConfig();
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }

        URI uri = requestContext.getUriInfo().getRequestUri();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        Origin origin = selectOrigin(cfg, path);
        if (origin == null) {
            return;
        }

        String bucket = extractBucketFromOriginDomain(origin.getDomainName());
        if (bucket == null) {
            return;
        }

        String newPath = buildOriginPath(bucket, origin.getOriginPath(), path, cfg.getDefaultRootObject());
        URI newUri = UriBuilder.fromUri(uri).replacePath(newPath).build();
        LOG.debugv("Routing CloudFront request {0}{1} -> {2}", hostname, path, newPath);
        requestContext.setRequestUri(newUri);
    }

    /**
     * Selects the origin that serves the given request path.
     *
     * <p>Cache behaviors are evaluated in order; the first whose path pattern
     * matches wins. If none match, the default cache behavior's origin is used.
     * Falls back to the first configured origin when no target can be resolved.
     */
    static Origin selectOrigin(DistributionConfig cfg, String rawPath) {
        List<Origin> origins = cfg.getOrigins();
        if (origins == null || origins.isEmpty()) {
            return null;
        }

        String relativePath = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        String targetOriginId = null;

        if (cfg.getCacheBehaviors() != null) {
            for (CacheBehavior cb : cfg.getCacheBehaviors()) {
                if (cb.getPathPattern() != null && globMatches(cb.getPathPattern(), relativePath)) {
                    targetOriginId = cb.getTargetOriginId();
                    break;
                }
            }
        }

        if (targetOriginId == null && cfg.getDefaultCacheBehavior() != null) {
            targetOriginId = cfg.getDefaultCacheBehavior().getTargetOriginId();
        }

        if (targetOriginId != null) {
            for (Origin o : origins) {
                if (targetOriginId.equals(o.getId())) {
                    return o;
                }
            }
        }

        return origins.get(0);
    }

    /**
     * Extracts the S3 bucket name from an origin's domain name.
     *
     * <p>CloudFront S3 origins use the virtual-hosted bucket domain, so the bucket
     * is the portion preceding the S3 service label. Handles bucket names that
     * contain dots (e.g. {@code my.bucket.s3.amazonaws.com}).
     *
     * <ul>
     *   <li>{@code bucket.s3.us-east-1.amazonaws.com} -&gt; {@code bucket}</li>
     *   <li>{@code bucket.s3.amazonaws.com} -&gt; {@code bucket}</li>
     *   <li>{@code bucket.s3.localhost.localstack.cloud} -&gt; {@code bucket}</li>
     *   <li>{@code bucket.s3-website-us-east-1.amazonaws.com} -&gt; {@code bucket}</li>
     *   <li>{@code my.bucket.s3.amazonaws.com} -&gt; {@code my.bucket}</li>
     * </ul>
     *
     * @return the bucket name, or null if the domain is not an S3 origin
     */
    static String extractBucketFromOriginDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return null;
        }
        String lower = domain.toLowerCase();
        int marker = firstIndexOf(lower, ".s3.", ".s3-");
        if (marker <= 0) {
            return null;
        }
        return domain.substring(0, marker);
    }

    /**
     * Builds the path-style S3 request path from the resolved bucket, origin path,
     * and viewer request path. Applies the default root object for root requests.
     */
    static String buildOriginPath(String bucket, String originPath, String rawPath, String defaultRootObject) {
        StringBuilder sb = new StringBuilder();
        sb.append('/').append(bucket);

        if (originPath != null && !originPath.isBlank()) {
            String op = originPath.trim();
            if (!op.startsWith("/")) {
                op = "/" + op;
            }
            while (op.endsWith("/")) {
                op = op.substring(0, op.length() - 1);
            }
            sb.append(op);
        }

        String key = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        if (key.equals("/") && defaultRootObject != null && !defaultRootObject.isBlank()) {
            String dro = defaultRootObject.startsWith("/") ? defaultRootObject : "/" + defaultRootObject;
            sb.append(dro);
        } else {
            if (!key.startsWith("/")) {
                sb.append('/');
            }
            sb.append(key);
        }
        return sb.toString();
    }

    /**
     * Matches a CloudFront path pattern against a request path (leading slash
     * stripped). Path patterns support the {@code *} and {@code ?} wildcards.
     */
    static boolean globMatches(String pattern, String relativePath) {
        String normalized = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString()).matcher(relativePath).matches();
    }

    private static int firstIndexOf(String haystack, String... needles) {
        int result = -1;
        for (String needle : needles) {
            int idx = haystack.indexOf(needle);
            if (idx >= 0 && (result < 0 || idx < result)) {
                result = idx;
            }
        }
        return result;
    }

    private static String stripPort(String host) {
        int colonIndex = host.lastIndexOf(':');
        if (colonIndex > 0) {
            String maybePort = host.substring(colonIndex + 1);
            if (!maybePort.isEmpty() && maybePort.chars().allMatch(Character::isDigit)) {
                return host.substring(0, colonIndex);
            }
        }
        return host;
    }
}
