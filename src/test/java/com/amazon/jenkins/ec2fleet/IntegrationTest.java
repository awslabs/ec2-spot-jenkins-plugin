package com.amazon.jenkins.ec2fleet;

import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlTextInput;
import hudson.PluginWrapper;
import hudson.slaves.Cloud;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Detailed guides https://jenkins.io/doc/developer/testing/
 * https://wiki.jenkins.io/display/JENKINS/Unit+Test#UnitTest-DealingwithproblemsinJavaScript
 */
public class IntegrationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();
    @ClassRule
    public static BuildWatcher bw = new BuildWatcher();

    @Test
    public void shouldFindThePluginByShortName() {
        PluginWrapper wrapper = j.getPluginManager().getPlugin("ec2-fleet");
        assertNotNull("should have a valid plugin", wrapper);
    }

    @Test
    public void shouldShowInConfigurationClouds() throws IOException, SAXException {
        Cloud cloud = new EC2FleetCloud(null, null, null, null,
                null, null, null, false, false,
                0, 0, 0, 0);
        j.jenkins.clouds.add(cloud);

        HtmlPage page = j.createWebClient().goTo("configure");
        System.out.println(page);

        assertEquals("ec2-fleet", ((HtmlTextInput) page.getElementsByName("_.labelString").get(1)).getText());
    }

}
