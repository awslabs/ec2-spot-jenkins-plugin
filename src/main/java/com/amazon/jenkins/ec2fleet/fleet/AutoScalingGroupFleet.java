package com.amazon.jenkins.ec2fleet.fleet;

import com.amazon.jenkins.ec2fleet.FleetStateStats;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.autoscaling.model.Instance;
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.springframework.util.ObjectUtils;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

// todo make sure we set scale in protection
@ThreadSafe
public class AutoScalingGroupFleet implements EC2Fleet {

    private static final String ACTIVE_STATE = "active";

    @Override
    public void describe(
            final String awsCredentialsId, final String regionName, final String endpoint,
            final ListBoxModel model, final String selectedId, final boolean showAll) {
        final AmazonAutoScalingClient client = createClient(awsCredentialsId, regionName, endpoint);
        String token = null;
        do {
            final DescribeAutoScalingGroupsRequest request = new DescribeAutoScalingGroupsRequest();
            request.withNextToken(token);
            final DescribeAutoScalingGroupsResult result = client.describeAutoScalingGroups(request);
            for (final AutoScalingGroup group : result.getAutoScalingGroups()) {
                final String curName = group.getAutoScalingGroupName();
                final boolean selected = ObjectUtils.nullSafeEquals(selectedId, curName);
                final String displayStr = "Auto Scaling Group - " + curName;
                model.add(new ListBoxModel.Option(displayStr, curName, selected));
            }
            token = result.getNextToken();
        } while (token != null);
    }

    @Override
    public void modify(String awsCredentialsId, String regionName, String endpoint, String id, int targetCapacity) {
        final AmazonAutoScalingClient client = createClient(awsCredentialsId, regionName, endpoint);
        client.updateAutoScalingGroup(
                new UpdateAutoScalingGroupRequest()
                        .withDesiredCapacity(targetCapacity)
                        .withAutoScalingGroupName(id));
    }

    @Override
    public FleetStateStats getState(String awsCredentialsId, String regionName, String endpoint, String id) {
        final AmazonAutoScalingClient client = createClient(awsCredentialsId, regionName, endpoint);
        final DescribeAutoScalingGroupsResult result = client.describeAutoScalingGroups(
                new DescribeAutoScalingGroupsRequest()
                        .withAutoScalingGroupNames(id));

        if (result.getAutoScalingGroups().isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "Cannot find auto scaling group with name %s in region %s", id, regionName));
        }

        final AutoScalingGroup group = result.getAutoScalingGroups().get(0);

        final Set<String> instanceIds = new HashSet<>(group.getInstances().size());
        for (final Instance instance : group.getInstances()) {
            instanceIds.add(instance.getInstanceId());
        }

        return new FleetStateStats(
                id, group.getDesiredCapacity(),
                // default status is null, so just put active to make EC2FleetCloud happy
                StringUtils.isNotEmpty(group.getStatus()) ? group.getStatus() : ACTIVE_STATE,
                // auto scaling groups don't support weight, may be in future
                instanceIds, Collections.<String, Double>emptyMap());
    }

    private AmazonAutoScalingClient createClient(String awsCredentialsId, String regionName, String endpoint) {
        final AmazonWebServicesCredentials credentials = AWSCredentialsHelper.getCredentials(awsCredentialsId, Jenkins.getInstance());
        final AmazonAutoScalingClient client = new AmazonAutoScalingClient(credentials);
        final String effectiveEndpoint = getEndpoint(regionName, endpoint);
        if (effectiveEndpoint != null) client.setEndpoint(effectiveEndpoint);
        return client;
    }

    // todo do we want to merge with EC2Api#getEndpoint
    @Nullable
    private String getEndpoint(@Nullable final String regionName, @Nullable final String endpoint) {
        if (StringUtils.isNotEmpty(endpoint)) {
            return endpoint;
        } else if (StringUtils.isNotEmpty(regionName)) {
            final Region region = RegionUtils.getRegion(regionName);
            if (region != null && region.isServiceSupported(endpoint)) {
                return region.getServiceEndpoint(endpoint);
            } else {
                final String domain = regionName.startsWith("cn-") ? "amazonaws.com.cn" : "amazonaws.com";
                return "https://autoscaling." + regionName + "." + domain;
            }
        } else {
            return null;
        }
    }

}
