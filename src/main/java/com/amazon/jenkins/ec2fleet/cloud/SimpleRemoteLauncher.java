package com.amazon.jenkins.ec2fleet.cloud;

import com.amazon.jenkins.ec2fleet.EC2FleetCloud;
import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.KeyPair;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.HTTPProxyData;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;
import hudson.ProxyConfiguration;
import hudson.model.Descriptor;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.UUID;
import java.util.logging.Level;

import static org.kohsuke.stapler.Facet.LOGGER;

/**
 * User: cyberax
 * Date: 4/15/16
 * Time: 00:55
 */
public class SimpleRemoteLauncher extends ComputerLauncher
{
    private final String address;

    public SimpleRemoteLauncher(final String address) {
        this.address = address;
    }

    @Override public boolean isLaunchSupported() {
        return true;
    }

    @Override public Descriptor<ComputerLauncher> getDescriptor() {
        return new Descriptor<ComputerLauncher>()
        {
            @Override public String getDisplayName() {
                return "Launch Fleet slaves";
            }
        };
    }

    @Override
    public void afterDisconnect(final SlaveComputer computer, final TaskListener listener) {
        final Slave node=computer.getNode();
        if(node!=null) {
            try {
                Jenkins.getInstance().removeNode(node);
            } catch(final IOException e) {
                e.printStackTrace(listener.error(e.getMessage()));
            }
        } else {
            listener.getLogger().printf("Could not remove node for %s as it appears to have been removed already%n", computer);
        }
    }

    protected void log(final Level level, final FleetNodeComputer computer, final TaskListener listener, final String message) {
        final EC2FleetCloud cloud = computer.getCloud();
        if (cloud != null)
            cloud.log(LOGGER, level, listener, message);
    }

    protected void logException(final FleetNodeComputer computer, final TaskListener listener, final String message, final Throwable exception) {
        final EC2FleetCloud cloud = computer.getCloud();
        if (cloud != null)
            cloud.log(LOGGER, Level.WARNING, listener, message, exception);
    }

    protected void logInfo(final FleetNodeComputer computer, final TaskListener listener, final String message) {
        log(Level.INFO, computer, listener, message);
    }

    protected void logWarning(final FleetNodeComputer computer, final TaskListener listener, final String message) {
        log(Level.WARNING, computer, listener, message);
    }

    @Override public void launch(final SlaveComputer rawComputer, final TaskListener listener)
            throws IOException, InterruptedException {
        final FleetNodeComputer computer =(FleetNodeComputer) rawComputer;
        final EC2FleetCloud cloud=computer.getCloud();

        final Connection conn;
        Connection cleanupConn = null; // java's code path analysis for final
        // doesn't work that well.
        boolean successful = false;
        final PrintStream logger = listener.getLogger();
        logInfo(computer, listener, "Launching instance: " + computer.getNode().getDisplayName());

        try {
            // connect fresh as ROOT
            logInfo(computer, listener, "connect fresh as root");
            cleanupConn = connectToSsh(computer, listener);
            final KeyPair key = new KeyPair();
            key.setKeyMaterial(cloud.getPrivateKey());
            if (!cleanupConn.authenticateWithPublicKey(cloud.getUserName(),
                    key.getKeyMaterial().toCharArray(), "")) {
                logWarning(computer, listener, "Authentication failed");
                return; // failed to connect as root.
            }
            conn = cleanupConn;

            final SCPClient scp = conn.createSCPClient();
            final String tmpDir = "/tmp/jenkins-code-"+UUID.randomUUID().toString().substring(0, 10);

            logInfo(computer, listener, "Creating tmp directory (" + tmpDir + ") if it does not exist");
            conn.exec("mkdir -p " + tmpDir, logger);

            logInfo(computer, listener, "Verifying that java exists");
            if (conn.exec("java -fullversion", logger) != 0) {
                logWarning(computer, listener, "No Java found on the host");
                return;
            }

            logInfo(computer, listener, "Copying slave.jar");
            scp.put(Jenkins.getInstance().getJnlpJars("slave.jar").readFully(), "slave.jar", tmpDir);

            final String jvmopts = cloud.getJvmSettings();
            final String launchString = "java " + (jvmopts != null ? jvmopts : "") + " -jar " + tmpDir + "/slave.jar";

            logInfo(computer, listener, "Launching slave agent (via Trilead SSH2 Connection): " + launchString);
            final Session sess = conn.openSession();
            sess.execCommand(launchString);
            computer.setChannel(sess.getStdout(), sess.getStdin(), logger, new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    sess.close();
                    conn.close();
                }
            });

            successful = true;
        } finally {
            if (cleanupConn != null && !successful)
                cleanupConn.close();
        }
    }

    private Connection connectToSsh(final FleetNodeComputer computer, final TaskListener listener) throws AmazonClientException,
            InterruptedException {
        final long timeout = computer.getNode().getLaunchTimeoutInMillis();
        final long startTime = System.currentTimeMillis();
        while (true) {
            try {
                final long waitTime = System.currentTimeMillis() - startTime;
                if (timeout > 0 && waitTime > timeout) {
                    throw new AmazonClientException("Timed out after " + (waitTime / 1000)
                            + " seconds of waiting for ssh to become available. (maximum timeout configured is "
                            + (timeout / 1000) + ")");
                }

                if ("0.0.0.0".equals(address)) {
                    logWarning(computer, listener, "Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }

                final Integer slaveConnectTimeout = Integer.getInteger("jenkins.ec2.slaveConnectTimeout", 10000);
                logInfo(computer, listener, "Connecting to " + address + " on port 22, with timeout " + slaveConnectTimeout
                        + ".");
                final Connection conn = new Connection(address, 22);
                final ProxyConfiguration proxyConfig = Jenkins.getInstance().proxy;
                final Proxy proxy = proxyConfig == null ? Proxy.NO_PROXY : proxyConfig.createProxy(address);
                if (!proxy.equals(Proxy.NO_PROXY) && proxy.address() instanceof InetSocketAddress) {
                    final InetSocketAddress address = (InetSocketAddress) proxy.address();
                    HTTPProxyData proxyData = null;
                    assert proxyConfig!=null;
                    if (null != proxyConfig.getUserName()) {
                        proxyData = new HTTPProxyData(address.getHostName(), address.getPort(), proxyConfig.getUserName(), proxyConfig.getPassword());
                    } else {
                        proxyData = new HTTPProxyData(address.getHostName(), address.getPort());
                    }
                    conn.setProxyData(proxyData);
                    logInfo(computer, listener, "Using HTTP Proxy Configuration");
                }
                conn.connect(new ServerHostKeyVerifier() {
                    public boolean verifyServerHostKey(final String hostname, final int port, final String serverHostKeyAlgorithm, final byte[] serverHostKey)
                            throws Exception {
                        return true;
                    }
                }, slaveConnectTimeout, slaveConnectTimeout);
                logInfo(computer, listener, "Connected via SSH.");
                return conn; // successfully connected
            } catch (final IOException e) {
                // keep retrying until SSH comes up
                logInfo(computer, listener, "Failed to connect via ssh: " + e.getMessage());
                logInfo(computer, listener, "Waiting for SSH to come up. Sleeping 5.");
                Thread.sleep(5000);
            }
        }
    }
}
