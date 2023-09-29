package com.amazon.jenkins.ec2fleet.fleet;

import com.amazon.jenkins.ec2fleet.aws.EC2Api;
import com.amazon.jenkins.ec2fleet.FleetStateStats;
import com.amazon.jenkins.ec2fleet.Registry;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;
import hudson.util.ListBoxModel;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class EC2CreateFleetTest {

    @Mock
    private AmazonEC2 ec2;

    @Mock
    private EC2Api ec2Api;

    @Before
    public void before() {
        Registry.setEc2Api(ec2Api);

        when(ec2Api.connect(anyString(), anyString(), anyString())).thenReturn(ec2);

        when(ec2.describeFleetInstances(any(DescribeFleetInstancesRequest.class)))
                .thenReturn(new DescribeFleetInstancesResult());

        when(ec2.describeFleets(any(DescribeFleetsRequest.class)))
                .thenReturn(new DescribeFleetsResult()
                        .withFleets(
                                new FleetData()
                                        .withTargetCapacitySpecification(
                                                new TargetCapacitySpecification()
                                                        .withTotalTargetCapacity(0))));
    }

    @After
    public void after() {
        Registry.setEc2Api(new EC2Api());
    }

    @Test(expected = IllegalStateException.class)
    public void getState_failIfNoFleet() {
        when(ec2.describeFleets(any(DescribeFleetsRequest.class)))
                .thenReturn(new DescribeFleetsResult());

        new EC2CreateFleet().getState("cred", "region", "", "f");
    }

    @Test
    public void getState_returnFleetInfo() {
        when(ec2.describeFleets(any(DescribeFleetsRequest.class)))
                .thenReturn(new DescribeFleetsResult()
                        .withFleets(
                                new FleetData()
                                        .withFleetState(String.valueOf(BatchState.Active))
                                        .withTargetCapacitySpecification(
                                                new TargetCapacitySpecification()
                                                        .withTotalTargetCapacity(12))));

        FleetStateStats stats = new EC2CreateFleet().getState("cred", "region", "", "f-id");

        Assert.assertEquals("f-id", stats.getFleetId());
        Assert.assertEquals(FleetStateStats.State.active(), stats.getState());
        Assert.assertEquals(12, stats.getNumDesired());
    }

    @Test
    public void getState_returnEmptyIfNoInstancesForFleet() {
        FleetStateStats stats = new EC2CreateFleet().getState("cred", "region", "", "f");

        Assert.assertEquals(Collections.emptySet(), stats.getInstances());
        Assert.assertEquals(0, stats.getNumActive());
    }

    @Test
    public void getState_returnAllDescribedInstancesForFleet() {
        when(ec2.describeFleetInstances(any(DescribeFleetInstancesRequest.class)))
                .thenReturn(new DescribeFleetInstancesResult()
                        .withActiveInstances(
                                new ActiveInstance().withInstanceId("i-1"),
                                new ActiveInstance().withInstanceId("i-2")));

        FleetStateStats stats = new EC2CreateFleet().getState("cred", "region", "", "f");

        Assert.assertEquals(new HashSet<>(Arrays.asList("i-1", "i-2")), stats.getInstances());
        Assert.assertEquals(2, stats.getNumActive());
        verify(ec2).describeFleetInstances(new DescribeFleetInstancesRequest()
                .withFleetId("f"));
    }

    @Test
    public void getState_returnAllPagesDescribedInstancesForFleet() {
        when(ec2.describeFleetInstances(any(DescribeFleetInstancesRequest.class)))
                .thenReturn(new DescribeFleetInstancesResult()
                        .withNextToken("p1")
                        .withActiveInstances(new ActiveInstance().withInstanceId("i-1")))
                .thenReturn(new DescribeFleetInstancesResult()
                        .withActiveInstances(new ActiveInstance().withInstanceId("i-2")));

        FleetStateStats stats = new EC2CreateFleet().getState("cred", "region", "", "f");

        Assert.assertEquals(new HashSet<>(Arrays.asList("i-1", "i-2")), stats.getInstances());
        Assert.assertEquals(2, stats.getNumActive());
        verify(ec2).describeFleetInstances(new DescribeFleetInstancesRequest()
                .withFleetId("f").withNextToken("p1"));
        verify(ec2).describeFleetInstances(new DescribeFleetInstancesRequest()
                .withFleetId("f"));
    }

    @Test
    public void getState_returnEmptyInstanceTypeWeightsIfNoInformation() {
        FleetStateStats stats = new EC2CreateFleet().getState("cred", "region", "", "f");

        Assert.assertEquals(Collections.emptyMap(), stats.getInstanceTypeWeights());
    }

    @Test
    public void getState_returnInstanceTypeWeightsFromLaunchSpecification() {
        when(ec2.describeFleets(any(DescribeFleetsRequest.class)))
                .thenReturn(new DescribeFleetsResult()
                        .withFleets(new FleetData()
                                .withFleetState(String.valueOf(BatchState.Active))
                                .withTargetCapacitySpecification(new TargetCapacitySpecification()
                                        .withTotalTargetCapacity(1))
                                .withLaunchTemplateConfigs(new FleetLaunchTemplateConfig()
                                        .withOverrides(
                                                new FleetLaunchTemplateOverrides().withInstanceType("t1").withWeightedCapacity(0.1),
                                                new FleetLaunchTemplateOverrides().withInstanceType("t2").withWeightedCapacity(12.0)))));

        FleetStateStats stats = new EC2CreateFleet().getState("cred", "region", "", "f");

        Map<String, Double> expected = new HashMap<>();
        expected.put("t1", 0.1);
        expected.put("t2", 12.0);
        Assert.assertEquals(expected, stats.getInstanceTypeWeights());
    }

    @Test
    public void getState_returnInstanceTypeWeightsForLaunchSpecificationIfItHasIt() {
        when(ec2.describeFleets(any(DescribeFleetsRequest.class)))
                .thenReturn(new DescribeFleetsResult()
                        .withFleets(new FleetData()
                                .withFleetState(String.valueOf(BatchState.Active))
                                .withTargetCapacitySpecification(new TargetCapacitySpecification()
                                        .withTotalTargetCapacity(1))
                                .withLaunchTemplateConfigs(new FleetLaunchTemplateConfig()
                                        .withOverrides(
                                                new FleetLaunchTemplateOverrides().withInstanceType("t1"),
                                                new FleetLaunchTemplateOverrides().withWeightedCapacity(12.0)))));

        FleetStateStats stats = new EC2CreateFleet().getState("cred", "region", "", "f");

        Assert.assertEquals(Collections.emptyMap(), stats.getInstanceTypeWeights());
    }

    @Test
    public void describe_whenAllFleetsEnabled_shouldIncludeAllFleetsInAllStates() {
        // given
        when(ec2.describeFleets(any(DescribeFleetsRequest.class)))
                .thenReturn(new DescribeFleetsResult().withFleets(
                        new FleetData()
                                .withFleetId("f1")
                                .withFleetState(String.valueOf(BatchState.Active))
                                .withType(FleetType.Maintain),
                        new FleetData()
                                .withFleetId("f2")
                                .withFleetState(String.valueOf(BatchState.Modifying))
                                .withType(FleetType.Request)));
        // when
        ListBoxModel model = new ListBoxModel();
        new EC2CreateFleet().describe("cred", "region", "", model, "selected", true);
        // then
        Assert.assertEquals(
                "[EC2 Spot Fleet - f1 (active) (maintain)=f1, EC2 Spot Fleet - f2 (modifying) (request)=f2]",
                model.toString());
    }

    @Test
    public void describe_whenAllFleetsDisabled_shouldSkipNonMaintain() {
        // given
        when(ec2.describeFleets(any(DescribeFleetsRequest.class)))
                .thenReturn(new DescribeFleetsResult().withFleets(
                        new FleetData()
                                .withFleetId("f1")
                                .withFleetState(String.valueOf(BatchState.Active))
                                .withType(FleetType.Maintain),
                        new FleetData()
                                .withFleetId("f2")
                                .withFleetState(String.valueOf(BatchState.Active))
                                .withType(FleetType.Request)));
        // when
        ListBoxModel model = new ListBoxModel();
        new EC2CreateFleet().describe("cred", "region", "", model, "selected", false);
        // then
        Assert.assertEquals(
                "[EC2 Spot Fleet - f1 (active) (maintain)=f1]",
                model.toString());
    }

    @Test
    public void describe_whenAllFleetsDisabled_shouldSkipNonCancelledOrFailed() {
        // given
        when(ec2.describeFleets(any(DescribeFleetsRequest.class)))
                .thenReturn(new DescribeFleetsResult().withFleets(
                        new FleetData()
                                .withFleetId("f1")
                                .withFleetState(String.valueOf(BatchState.Active))
                                .withType(FleetType.Maintain),
                        new FleetData()
                                .withFleetId("f2")
                                .withFleetState(String.valueOf(BatchState.Cancelled_running))
                                .withType(FleetType.Maintain),
                        new FleetData()
                                .withFleetId("f3")
                                .withFleetState(String.valueOf(BatchState.Cancelled_terminating))
                                .withType(FleetType.Maintain),
                        new FleetData()
                                .withFleetId("f3")
                                .withFleetState(String.valueOf(BatchState.Failed))
                                .withType(FleetType.Maintain)));
        // when
        ListBoxModel model = new ListBoxModel();
        new EC2CreateFleet().describe("cred", "region", "", model, "selected", false);
        // then
        Assert.assertEquals(
                "[EC2 Spot Fleet - f1 (active) (maintain)=f1]",
                model.toString());
    }

    @Test
    public void describe_whenAllFleetsDisabled_shouldIncludeSubmittedModifiedActive() {
        // given
        when(ec2.describeFleets(any(DescribeFleetsRequest.class)))
                .thenReturn(new DescribeFleetsResult().withFleets(
                        new FleetData()
                                .withFleetId("f1")
                                .withFleetState(String.valueOf(BatchState.Active))
                                .withType(FleetType.Maintain),
                        new FleetData()
                                .withFleetId("f2")
                                .withFleetState(String.valueOf(BatchState.Submitted))
                                .withType(FleetType.Maintain),
                        new FleetData()
                                .withFleetId("f3")
                                .withFleetState(String.valueOf(BatchState.Modifying))
                                .withType(FleetType.Maintain)));
        // when
        ListBoxModel model = new ListBoxModel();
        new EC2CreateFleet().describe("cred", "region", "", model, "selected", false);
        // then
        Assert.assertEquals(
                "[EC2 Spot Fleet - f1 (active) (maintain)=f1, EC2 Spot Fleet - f2 (submitted) (maintain)=f2, EC2 Spot Fleet - f3 (modifying) (maintain)=f3]",
                model.toString());
    }
}