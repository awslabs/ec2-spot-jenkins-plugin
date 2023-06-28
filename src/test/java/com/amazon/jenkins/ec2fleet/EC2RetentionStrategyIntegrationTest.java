package com.amazon.jenkins.ec2fleet;

import com.amazon.jenkins.ec2fleet.fleet.EC2Fleet;
import com.amazon.jenkins.ec2fleet.fleet.EC2Fleets;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceState;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlFormUtil;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlTextInput;
import hudson.model.Node;
import hudson.model.queue.QueueTaskFuture;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class EC2RetentionStrategyIntegrationTest extends IntegrationTest {

    private AmazonEC2 amazonEC2;

    @Before
    public void before() {
        final EC2Fleet ec2Fleet = mock(EC2Fleet.class);
        EC2Fleets.setGet(ec2Fleet);
        final EC2Api ec2Api = spy(EC2Api.class);
        Registry.setEc2Api(ec2Api);
        amazonEC2 = mock(AmazonEC2.class);

        when(ec2Fleet.getState(anyString(), anyString(), nullable(String.class), anyString()))
                .thenReturn(new FleetStateStats("", 2, FleetStateStats.State.active(), new HashSet<>(Arrays.asList("i-1", "i-2")), Collections.emptyMap()));
        when(ec2Api.connect(anyString(), anyString(), Mockito.nullable(String.class))).thenReturn(amazonEC2);

        final Instance instance = new Instance()
                .withState(new InstanceState().withName(InstanceStateName.Running))
                .withPublicIpAddress("public-io")
                .withInstanceId("i-1");
        final Instance instance1 = new Instance()
                .withState(new InstanceState().withName(InstanceStateName.Running))
                .withPublicIpAddress("public-io")
                .withInstanceId("i-2");

        when(amazonEC2.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(
                new DescribeInstancesResult().withReservations(
                        new Reservation().withInstances(
                                instance, instance1
                        )));
        when(amazonEC2.terminateInstances(any(TerminateInstancesRequest.class))).thenReturn(new TerminateInstancesResult());
    }

    @Test
    public void shouldTerminateNodeMarkedForDeletion() throws Exception {
        final EC2FleetCloud cloud = new EC2FleetCloud(null, null, "credId", null, "region",
                null, "fId", "momo", null, new LocalComputerConnector(j), false, false,
                1, 0, 0, 0, 1, false, true, "-1", false, 0, 0, false, 999, false);
        // Set initial jenkins nodes
        cloud.update();
        j.jenkins.clouds.add(cloud);

        assertAtLeastOneNode();

        EC2FleetNode node = (EC2FleetNode) j.jenkins.getNode("i-1");
        EC2FleetNodeComputer c = (EC2FleetNodeComputer) node.toComputer();
        c.doDoDelete(); // mark node for termination
        node.getRetentionStrategy().check(c);

        // Make sure the scheduled for termination instances are terminated
        cloud.update();

        final ArgumentCaptor<TerminateInstancesRequest> argument = ArgumentCaptor.forClass(TerminateInstancesRequest.class);
        verify(amazonEC2, times(1)).terminateInstances(argument.capture());
        assertTrue(argument.getAllValues().get(0).getInstanceIds().containsAll(Arrays.asList("i-1")));
    }

    @Test
    public void shouldTerminateExcessCapacity() throws Exception {
        final EC2FleetCloud cloud = new EC2FleetCloud(null, null, "credId", null, "region",
                null, "fId", "momo", null, new LocalComputerConnector(j), false, false,
                1, 0, 0, 0, 1, false, true, "-1", false, 0, 0, false, 999, false);
        // Set initial jenkins nodes
        cloud.update();
        j.jenkins.clouds.add(cloud);

        assertAtLeastOneNode();

        final ArgumentCaptor<TerminateInstancesRequest> argument = ArgumentCaptor.forClass(TerminateInstancesRequest.class);

        // Nodes take a minute to become idle
        Thread.sleep(1000 * 61);
        // Manually trigger the retention check because it's super flaky whether it actually gets triggered
        for (final Node node : j.jenkins.getNodes()) {
            if (node instanceof EC2FleetNode && ((EC2FleetNode) node).getCloud() == cloud) {
                EC2FleetNodeComputer computer = (EC2FleetNodeComputer) ((EC2FleetNode) node).getComputer();
                new EC2RetentionStrategy().check(computer);
            }
        }

        // Make sure the scheduled for termination instances are terminated
        cloud.update();

        verify((amazonEC2), times(1)).terminateInstances(argument.capture());

        final List<String> instanceIds = new ArrayList<String>();
        instanceIds.add("i-2");
        instanceIds.add("i-1");

        assertTrue(argument.getAllValues().get(0).getInstanceIds().containsAll(instanceIds));
    }

    @Test
    public void shouldNotTerminateExcessCapacityWhenNodeIsBusy() throws Exception {
        // Keep a busy queue
        List<QueueTaskFuture> rs = enqueTask(10, 90);
        triggerSuggestReviewNow();

        EC2FleetCloud cloud = new EC2FleetCloud(null, "null", "credId", null, "region",
                null, "fId", "momo", null, new LocalComputerConnector(j), false, false,
                1, 2, 2, 0, 1, false, true, "-1", false, 0, 0, false, 999, false);
        j.jenkins.clouds.add(cloud);
        cloud.update();

        assertAtLeastOneNode();
        cloud = new EC2FleetCloud(null, null, "credId", null, "region",
                null, "fId", "momo", null, new LocalComputerConnector(j), false, false,
                1, 0, 0, 0, 1, false, true, "-1", false, 0, 0, false, 99, false);
        j.jenkins.clouds.clear();
        j.jenkins.clouds.add(cloud);
        assertAtLeastOneNode();
        cloud.update();
        final ArgumentCaptor<TerminateInstancesRequest> argument = ArgumentCaptor.forClass(TerminateInstancesRequest.class);

        // Nodes take a minute to become idle
        Thread.sleep(1000 * 61);
        // Manually trigger the retention check because it's super flaky whether it actually gets triggered
        for (final Node node : j.jenkins.getNodes()) {
            if (node instanceof EC2FleetNode && ((EC2FleetNode) node).getCloud() == cloud) {
                EC2FleetNodeComputer computer = (EC2FleetNodeComputer) ((EC2FleetNode) node).getComputer();
                new EC2RetentionStrategy().check(computer);
            }
        }
        cloud.update();

        verify((amazonEC2), times(0)).terminateInstances(any());
        cancelTasks(rs);
    }

    @Test
    public void shouldTerminateIdleNodesAfterIdleTimeout() throws Exception {
        final EC2FleetCloud cloud = new EC2FleetCloud(null, null, "credId", null, "region",
                null, "fId", "momo", null, new LocalComputerConnector(j), false, false,
                1, 0, 2, 0, 1, false, true, "-1", false, 0, 0, false, 99, false);
        j.jenkins.clouds.add(cloud);
        cloud.update();

        assertAtLeastOneNode();

        final ArgumentCaptor<TerminateInstancesRequest> argument = ArgumentCaptor.forClass(TerminateInstancesRequest.class);

        // Nodes take a minute to become idle
        Thread.sleep(1000 * 61);
        // Manually trigger the retention check because it's super flaky whether it actually gets triggered
        for (final Node node : j.jenkins.getNodes()) {
            if (node instanceof EC2FleetNode && ((EC2FleetNode) node).getCloud() == cloud) {
                EC2FleetNodeComputer computer = (EC2FleetNodeComputer) ((EC2FleetNode) node).getComputer();
                new EC2RetentionStrategy().check(computer);
            }
        }
        cloud.update();

        verify((amazonEC2), times(1)).terminateInstances(argument.capture());

        final List<String> instanceIds = new ArrayList<String>();
        instanceIds.add("i-2");
        instanceIds.add("i-1");
        assertTrue(argument.getAllValues().get(0).getInstanceIds().containsAll(instanceIds));
    }

    @Test
    public void shouldNotTerminateBelowMinSize() throws Exception {
        final EC2FleetCloud cloud = new EC2FleetCloud(null, null, "credId", null, "region",
                null, "fId", "momo", null, new LocalComputerConnector(j), false, false,
                1, 2, 5, 0, 1, false, true, "-1", false, 0, 0, false, 30, false);
        j.jenkins.clouds.add(cloud);
        cloud.update();

        assertAtLeastOneNode();

        // Nodes take a minute to become idle
        Thread.sleep(1000 * 61);
        // Manually trigger the retention check because it's super flaky whether it actually gets triggered
        for (final Node node : j.jenkins.getNodes()) {
            if (node instanceof EC2FleetNode && ((EC2FleetNode) node).getCloud() == cloud) {
                EC2FleetNodeComputer computer = (EC2FleetNodeComputer) ((EC2FleetNode) node).getComputer();
                new EC2RetentionStrategy().check(computer);
            }
        }
        cloud.update();

        verify((amazonEC2), times(0)).terminateInstances(any());
    }

    @Test
    public void shouldNotTerminateBelowMinSpareSize() throws Exception {
        final EC2FleetCloud cloud = new EC2FleetCloud(null, null, "credId", null, "region",
                null, "fId", "momo", null, new LocalComputerConnector(j), false, false,
                1, 0, 5, 2, 1, false, true, "-1", false, 0, 0, false, 30, false);
        j.jenkins.clouds.add(cloud);
        cloud.update();

        assertAtLeastOneNode();

        // Nodes take a minute to become idle
        Thread.sleep(1000 * 61);
        // Manually trigger the retention check because it's super flaky whether it actually gets triggered
        for (final Node node : j.jenkins.getNodes()) {
            if (node instanceof EC2FleetNode && ((EC2FleetNode) node).getCloud() == cloud) {
                EC2FleetNodeComputer computer = (EC2FleetNodeComputer) ((EC2FleetNode) node).getComputer();
                new EC2RetentionStrategy().check(computer);
            }
        }
        cloud.update();

        verify((amazonEC2), times(0)).terminateInstances(any());
    }

    @Test
    public void shouldTerminateWhenMaxTotalUsesIsExhausted() throws Exception {
        final String label = "momo";
        final int numTasks = 4; // schedule a total of 4 tasks, 2 per instance
        final int maxTotalUses = 2;
        final int taskSleepTime = 1;

        EC2FleetCloud cloud = spy(new EC2FleetCloud("testCloud", null, "credId", null, "region",
                null, "fId", label, null, new LocalComputerConnector(j), false, false,
                0, 0, 10, 0, 1, false, true,
                String.valueOf(maxTotalUses), true, 0, 0, false, 10, false));
        j.jenkins.clouds.add(cloud);
        cloud.update();
        assertAtLeastOneNode();

        System.out.println("*** scheduling tasks ***");
        waitJobSuccessfulExecution(enqueTask(numTasks, taskSleepTime));
        Thread.sleep(3000); // sleep for a bit to make sure post job actions finish and the computers are idle

        // make sure the instances scheduled for termination are terminated
        cloud.update();

        final ArgumentCaptor<TerminateInstancesRequest> argument = ArgumentCaptor.forClass(TerminateInstancesRequest.class);
        verify((amazonEC2)).terminateInstances(argument.capture());
        assertTrue(argument.getAllValues().get(0).getInstanceIds().containsAll(Arrays.asList("i-1", "i-2")));
    }

    @Test
    public void shouldTerminateNodeForMaxTotalUsesIsExhaustedAfterConfigChange() throws Exception {
        final String label = "momo";
        final int numTasks = 4; // schedule a total of 4 tasks, 2 per instance
        final int maxTotalUses = 2;
        final long scheduleInterval = 5;
        final int cloudStatusInternalSec = 60; // increase to trigger update manually
        final int taskSleepTime = 1;

        EC2FleetCloud cloud = new EC2FleetCloud("testCloud", null, "credId", null, "region",
                null, "fId", label, null, new LocalComputerConnector(j), false, false,
                0, 0, 10, 0, 1, false, true,
                String.valueOf(maxTotalUses), true, 0, 0, false,
                cloudStatusInternalSec, false);
        j.jenkins.clouds.add(cloud);
        cloud.update();
        assertAtLeastOneNode();

        // initiate a config change after a node exhausts maxTotalUses and is scheduled for termination
        EC2FleetCloud newCloud;
        String nodeToTerminate = null;
        int taskCount = 0;
        final List<QueueTaskFuture> tasks = new ArrayList<>();
        for (int i=numTasks; i > 0 ; i--) {
            // get first node that is about to get terminated, before scheduling more tasks
            List<String> nodesWithExhaustedMaxUses = j.jenkins.getNodes().stream()
                    .filter(n -> ((EC2FleetNode)n).getUsesRemaining() == 0)
                    .map(Node::getNodeName)
                    .collect(Collectors.toList());
            if (nodesWithExhaustedMaxUses != null && !nodesWithExhaustedMaxUses.isEmpty()) {
                nodeToTerminate = nodesWithExhaustedMaxUses.get(0);
                break; // we have what we want, stop scheduling more tasks - exit loop and verify
            }

            // schedule a task
            tasks.addAll(enqueTask(1, taskSleepTime));
            taskCount++;
            System.out.println("scheduled task " + taskCount + ", waiting " + scheduleInterval + " sec");
            Thread.sleep(TimeUnit.SECONDS.toMillis(scheduleInterval));
        }
        waitJobSuccessfulExecution(tasks); // wait for scheduleToTerminate to be called

        assertNotNull(nodeToTerminate);

        // make a config change after a node is scheduled to terminate
        HtmlPage page = j.createWebClient().goTo("configureClouds");
        HtmlForm form = page.getFormByName("config");
        System.out.println(form.toString());
        System.out.println(IntegrationTest.getElementsByNameWithoutJdk(page, "_.name"));
        ((HtmlTextInput) IntegrationTest.getElementsByNameWithoutJdk(page, "_.name").get(0)).setText("new-name");
        HtmlFormUtil.submit(form);

        // verify cloud object was re-created, leading to lost state (i.e. instanceIdsToTerminate)
        newCloud = (EC2FleetCloud) j.jenkins.clouds.get(0);
        assertNotSame(cloud, newCloud);
        assertTrue(cloud.getInstanceIdsToTerminate().containsKey(nodeToTerminate));
        assertTrue(newCloud.getInstanceIdsToTerminate().isEmpty());

        // initiate check to schedule instance to terminate again
        EC2FleetNode node = (EC2FleetNode) j.jenkins.getNode(nodeToTerminate);
        node.getRetentionStrategy().check(node.toComputer());

        // terminate scheduled instances
        cloud.update();

        final ArgumentCaptor<TerminateInstancesRequest> argument = ArgumentCaptor.forClass(TerminateInstancesRequest.class);
        verify((amazonEC2)).terminateInstances(argument.capture());
        assertTrue(argument.getAllValues().get(0).getInstanceIds().contains(nodeToTerminate));
    }
}
