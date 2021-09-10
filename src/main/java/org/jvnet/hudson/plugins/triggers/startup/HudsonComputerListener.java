/*
  The MIT License
  Copyright (c) 2015 Ash Lux, Gregory Boissinot and all contributors
  <p/>
  Permission is hereby granted, free of charge, to any person obtaining a copy
  of this software and associated documentation files (the "Software"), to deal
  in the Software without restriction, including without limitation the rights
  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  copies of the Software, and to permit persons to whom the Software is
  furnished to do so, subject to the following conditions:
  <p/>
  The above copyright notice and this permission notice shall be included in
  all copies or substantial portions of the Software.
  <p/>
  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
  THE SOFTWARE.
 */
package org.jvnet.hudson.plugins.triggers.startup;

import hudson.Extension;
import hudson.model.*;
import hudson.slaves.ComputerListener;
import hudson.triggers.Trigger;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jvnet.jenkins.plugins.nodelabelparameter.NodeParameterValue;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Gregory Boissinot
 */
@Extension
public class HudsonComputerListener extends ComputerListener {

    private final HudsonStartupService startupService = new HudsonStartupService();

    private static ParametersAction getDefaultParameters(Job<?, ?> project) {
        ParametersDefinitionProperty property = (ParametersDefinitionProperty) project.getProperty(ParametersDefinitionProperty.class);

        if (property == null) {
            return new ParametersAction();
        }

        List<ParameterValue> parameters = new ArrayList<>();
        for (ParameterDefinition pd : property.getParameterDefinitions()) {
            ParameterValue param = pd.getDefaultParameterValue();
            if (param != null) {
                parameters.add(param);
            }
        }

        return new ParametersAction(parameters);
    }

    private static String getParameterType(Job<?, ?> project, String nodeParameterName) {
        ParametersDefinitionProperty property = (ParametersDefinitionProperty) project.getProperty(ParametersDefinitionProperty.class);

        if (property == null) {
            return null;
        }

        for (ParameterDefinition pd : property.getParameterDefinitions()) {
            ParameterValue param = pd.getDefaultParameterValue();
            if (param != null) {
                if (param.getName().equals(nodeParameterName)) {
                    return pd.getType();
                }
            }
        }

        return null;
    }

    @Override
    public void onTemporarilyOnline(Computer c) {
        handleConnect("ON_CONNECT", c, null);
    }

    @Override
    public void onOnline(Computer c, TaskListener listener) {
        handleConnect("ON_ONLINE", c, listener);
    }

    private void handleConnect(String connectionNotType, Computer c, TaskListener listener) {
        Node node = c.getNode();
        if (node != null) {
            if (listener != null) {
                listener.getLogger().println("[StartupTrigger] - Scanning jobs for node " + getNodeName(node));
            }
            Jenkins jenkinsInstance = Jenkins.get();
            if (jenkinsInstance != null) {
                List<Job> jobs = jenkinsInstance.getAllItems(Job.class);

                for (Job<?, ?> job : jobs) {
                    if (job instanceof ParameterizedJobMixIn.ParameterizedJob) {
                        ParameterizedJobMixIn.ParameterizedJob<?, ?> pJob = (ParameterizedJobMixIn.ParameterizedJob<?, ?>) job;

                        for (Trigger<?> trigger : ((ParameterizedJobMixIn.ParameterizedJob<?, ?>) pJob).getTriggers().values()) {
                            if (trigger instanceof HudsonStartupTrigger) {
                                HudsonStartupTrigger startupTrigger = (HudsonStartupTrigger) trigger;

                                if (!startupTrigger.getRunOnChoice().equals(connectionNotType)) {
                                    processAndScheduleIfNeeded(job, c, listener, startupTrigger);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private String getNodeName(Node node) {
        String nodeName = node.getNodeName();
        if ("".equals(nodeName)) {
            return "master";
        }
        return nodeName;
    }

    private void processAndScheduleIfNeeded(Job<?, ?> project, Computer c, TaskListener listener, HudsonStartupTrigger startupTrigger) {
        Node node = c.getNode();
        if (node == null) {
            return;
        }

        if (startupService.has2Schedule(startupTrigger, node) && project.isBuildable()) {
            if (listener != null) {
                listener.getLogger().println("[StartupTrigger] - Scheduling " + project.getName());
            }

            ParametersAction scheduleParameters = getDefaultParameters(project);
            if (startupTrigger.getNodeParameterName() != null) {
                String parameterType = getParameterType(project, startupTrigger.getNodeParameterName());
                String nodeName = node.getNodeName();

                if (nodeName.equals("")) {
                    nodeName = "master";
                }
                if (parameterType != null) {
                    switch (parameterType) {
                        case "NodeParameterDefinition":
                        case "LabelParameterDefinition":
                            scheduleParameters = scheduleParameters.merge(new ParametersAction(new NodeParameterValue(startupTrigger.getNodeParameterName(), "", nodeName)));
                            break;
                        case "StringParameterDefinition":
                            scheduleParameters = scheduleParameters.merge(new ParametersAction(new StringParameterValue(startupTrigger.getNodeParameterName(), nodeName)));
                            break;
                        default:
                            break;
                    }
                }
            }

            scheduleBuild(project, startupTrigger.getQuietPeriod(), new HudsonStartupCause(node), scheduleParameters);
        }
    }

    private void scheduleBuild(Job<?, ?> job, int quietPeriod, HudsonStartupCause startupCause, ParametersAction scheduleParameters) {
        if (job instanceof AbstractProject) {
            AbstractProject<?, ?> project = (AbstractProject<?, ?>) job;
            project.scheduleBuild(quietPeriod, startupCause, scheduleParameters);
        } else if (job instanceof WorkflowJob) {
            WorkflowJob project = (WorkflowJob) job;
            project.scheduleBuild2(quietPeriod, scheduleParameters);
        }
    }
}
