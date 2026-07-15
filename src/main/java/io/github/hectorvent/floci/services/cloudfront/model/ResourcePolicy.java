package io.github.hectorvent.floci.services.cloudfront.model;

public class ResourcePolicy {

    private String resourceArn;
    private String policyDocument;

    public ResourcePolicy() {}

    public ResourcePolicy(String resourceArn, String policyDocument) {
        this.resourceArn = resourceArn;
        this.policyDocument = policyDocument;
    }

    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String resourceArn) { this.resourceArn = resourceArn; }

    public String getPolicyDocument() { return policyDocument; }
    public void setPolicyDocument(String policyDocument) { this.policyDocument = policyDocument; }
}
