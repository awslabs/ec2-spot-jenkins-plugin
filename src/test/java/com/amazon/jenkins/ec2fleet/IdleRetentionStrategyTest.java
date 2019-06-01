package com.amazon.jenkins.ec2fleet;

import hudson.model.Slave;
import hudson.slaves.SlaveComputer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(PowerMockRunner.class)
@PrepareForTest(SlaveComputer.class)
public class IdleRetentionStrategyTest {

    @Mock
    private EC2FleetCloud cloud;

    @Mock
    private SlaveComputer slaveComputer;

    @Mock
    private Slave slave;

    @Test
    public void if_idle_time_not_configured_should_do_nothing() {
        when(slaveComputer.isAcceptingTasks()).thenReturn(true);

        new IdleRetentionStrategy(cloud).check(slaveComputer);

        verify(slaveComputer, times(0)).getNode();
        verify(cloud, times(0)).terminateInstance(anyString());
        verify(slaveComputer).setAcceptingTasks(false);
        verify(slaveComputer).setAcceptingTasks(true);
    }

    @Test
    public void if_idle_time_configured_should_do_nothing_if_node_idle_less_time() {
        when(slaveComputer.isAcceptingTasks()).thenReturn(true);
        when(cloud.getIdleMinutes()).thenReturn(10);
        when(slaveComputer.getIdleStartMilliseconds()).thenReturn(System.currentTimeMillis());

        new IdleRetentionStrategy(cloud).check(slaveComputer);

        verify(slaveComputer, times(0)).getNode();
        verify(cloud, times(0)).terminateInstance(anyString());
        verify(slaveComputer).setAcceptingTasks(false);
        verify(slaveComputer).setAcceptingTasks(true);
    }

    @Test
    public void if_idle_time_configured_should_terminate_node_if_idle_time_more_then_allowed() {
        when(cloud.getIdleMinutes()).thenReturn(10);
        when(slaveComputer.getIdleStartMilliseconds()).thenReturn(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(11));
        when(slaveComputer.getNode()).thenReturn(slave);
        when(slave.getNodeName()).thenReturn("n-a");

        new IdleRetentionStrategy(cloud).check(slaveComputer);

        verify(cloud, times(1)).terminateInstance("n-a");
        verify(slaveComputer, times(2)).setAcceptingTasks(false);
        verify(slaveComputer, times(0)).setAcceptingTasks(true);
    }

    @Test
    public void if_exception_happen_during_termination_should_throw_it_and_restore_task_accepting() {
        when(cloud.terminateInstance(anyString())).thenThrow(new IllegalArgumentException("test"));
        when(cloud.getIdleMinutes()).thenReturn(10);
        when(slaveComputer.isAcceptingTasks()).thenReturn(true);
        when(slaveComputer.getIdleStartMilliseconds()).thenReturn(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(11));
        when(slaveComputer.getNode()).thenReturn(slave);
        when(slave.getNodeName()).thenReturn("n-a");

        try {
            new IdleRetentionStrategy(cloud).check(slaveComputer);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("test", e.getMessage());
            verify(cloud, times(1)).terminateInstance("n-a");
            verify(slaveComputer).setAcceptingTasks(false);
            verify(slaveComputer).setAcceptingTasks(true);
        }
    }

}
