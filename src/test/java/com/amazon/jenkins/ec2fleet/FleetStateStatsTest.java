package com.amazon.jenkins.ec2fleet;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.ActiveInstance;
import com.amazonaws.services.ec2.model.BatchState;
import com.amazonaws.services.ec2.model.DescribeSpotFleetInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeSpotFleetInstancesResult;
import com.amazonaws.services.ec2.model.DescribeSpotFleetRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotFleetRequestsResult;
import com.amazonaws.services.ec2.model.LaunchTemplateConfig;
import com.amazonaws.services.ec2.model.SpotFleetLaunchSpecification;
import com.amazonaws.services.ec2.model.SpotFleetRequestConfig;
import com.amazonaws.services.ec2.model.SpotFleetRequestConfigData;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class FleetStateStatsTest {

    @Mock
    private AmazonEC2 ec2;

    @Before
    public void before() {
        when(ec2.describeSpotFleetInstances(any(DescribeSpotFleetInstancesRequest.class)))
                .thenReturn(new DescribeSpotFleetInstancesResult());

        when(ec2.describeSpotFleetRequests(any(DescribeSpotFleetRequestsRequest.class)))
                .thenReturn(new DescribeSpotFleetRequestsResult()
                        .withSpotFleetRequestConfigs(
                                new SpotFleetRequestConfig()
                                        .withSpotFleetRequestConfig(
                                                new SpotFleetRequestConfigData()
                                                        .withTargetCapacity(0))));
    }

    @Test(expected = IllegalStateException.class)
    public void readClusterState_failIfNoFleet() {
        when(ec2.describeSpotFleetRequests(any(DescribeSpotFleetRequestsRequest.class)))
                .thenReturn(new DescribeSpotFleetRequestsResult());

        FleetStateStats.readClusterState(ec2, "f", "");
    }

    @Test
    public void readClusterState_returnFleetInfo() {
        when(ec2.describeSpotFleetRequests(any(DescribeSpotFleetRequestsRequest.class)))
                .thenReturn(new DescribeSpotFleetRequestsResult()
                        .withSpotFleetRequestConfigs(
                                new SpotFleetRequestConfig()
                                        .withSpotFleetRequestState(BatchState.Active)
                                        .withSpotFleetRequestConfig(
                                                new SpotFleetRequestConfigData()
                                                        .withTargetCapacity(12))));

        FleetStateStats stats = FleetStateStats.readClusterState(ec2, "f-id", "");

        Assert.assertEquals("f-id", stats.getFleetId());
        Assert.assertEquals("active", stats.getState());
        Assert.assertEquals(12, stats.getNumDesired());
    }

    @Test
    public void readClusterState_returnEmptyIfNoInstancesForFleet() {
        FleetStateStats stats = FleetStateStats.readClusterState(ec2, "f", "");

        Assert.assertEquals(Collections.emptySet(), stats.getInstances());
        Assert.assertEquals(0, stats.getNumActive());
    }

    @Test
    public void readClusterState_returnAllDescribedInstancesForFleet() {
        when(ec2.describeSpotFleetInstances(any(DescribeSpotFleetInstancesRequest.class)))
                .thenReturn(new DescribeSpotFleetInstancesResult()
                        .withActiveInstances(
                                new ActiveInstance().withInstanceId("i-1"),
                                new ActiveInstance().withInstanceId("i-2")));

        FleetStateStats stats = FleetStateStats.readClusterState(ec2, "f", "");

        Assert.assertEquals(ImmutableSet.of("i-1", "i-2"), stats.getInstances());
        Assert.assertEquals(2, stats.getNumActive());
        verify(ec2).describeSpotFleetInstances(new DescribeSpotFleetInstancesRequest()
                .withSpotFleetRequestId("f"));
    }

    @Test
    public void readClusterState_returnAllPagesDescribedInstancesForFleet() {
        when(ec2.describeSpotFleetInstances(any(DescribeSpotFleetInstancesRequest.class)))
                .thenReturn(new DescribeSpotFleetInstancesResult()
                        .withNextToken("p1")
                        .withActiveInstances(new ActiveInstance().withInstanceId("i-1")))
                .thenReturn(new DescribeSpotFleetInstancesResult()
                        .withActiveInstances(new ActiveInstance().withInstanceId("i-2")));

        FleetStateStats stats = FleetStateStats.readClusterState(ec2, "f", "");

        Assert.assertEquals(ImmutableSet.of("i-1", "i-2"), stats.getInstances());
        Assert.assertEquals(2, stats.getNumActive());
        verify(ec2).describeSpotFleetInstances(new DescribeSpotFleetInstancesRequest()
                .withSpotFleetRequestId("f").withNextToken("p1"));
        verify(ec2).describeSpotFleetInstances(new DescribeSpotFleetInstancesRequest()
                .withSpotFleetRequestId("f"));
    }

    @Test
    public void readClusterState_returnEmptyInstanceTypeWeightsIfNoInformation() {
        FleetStateStats stats = FleetStateStats.readClusterState(ec2, "f", "");

        Assert.assertEquals(Collections.emptyMap(), stats.getInstanceTypeWeights());
    }

    @Test
    public void readClusterState_returnInstanceTypeWeightsFromLaunchSpecification() {
        when(ec2.describeSpotFleetRequests(any(DescribeSpotFleetRequestsRequest.class)))
                .thenReturn(new DescribeSpotFleetRequestsResult()
                        .withSpotFleetRequestConfigs(new SpotFleetRequestConfig()
                                .withSpotFleetRequestState(BatchState.Active)
                                .withSpotFleetRequestConfig(new SpotFleetRequestConfigData()
                                        .withTargetCapacity(1)
                                        .withLaunchSpecifications(
                                                new SpotFleetLaunchSpecification().withInstanceType("t1").withWeightedCapacity(0.1),
                                                new SpotFleetLaunchSpecification().withInstanceType("t2").withWeightedCapacity(12.0)))));

        FleetStateStats stats = FleetStateStats.readClusterState(ec2, "f", "");

        Assert.assertEquals(ImmutableMap.of("t1", 0.1, "t2", 12.0), stats.getInstanceTypeWeights());
    }

    @Test
    public void readClusterState_returnInstanceTypeWeightsForLaunchSpecificationIfItHasIt() {
        when(ec2.describeSpotFleetRequests(any(DescribeSpotFleetRequestsRequest.class)))
                .thenReturn(new DescribeSpotFleetRequestsResult()
                        .withSpotFleetRequestConfigs(new SpotFleetRequestConfig()
                                .withSpotFleetRequestState(BatchState.Active)
                                .withSpotFleetRequestConfig(new SpotFleetRequestConfigData()
                                        .withTargetCapacity(1)
                                        .withLaunchSpecifications(
                                                new SpotFleetLaunchSpecification().withInstanceType("t1"),
                                                new SpotFleetLaunchSpecification().withWeightedCapacity(12.0)))));

        FleetStateStats stats = FleetStateStats.readClusterState(ec2, "f", "");

        Assert.assertEquals(Collections.emptyMap(), stats.getInstanceTypeWeights());
    }

}
