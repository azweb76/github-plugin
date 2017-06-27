package com.cloudbees.jenkins;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentContributor;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


@Restricted(NoExternalUse.class)
public class GitHubParametersAction extends ParametersAction {

    private List<ParameterValue> parameters;

    public GitHubParametersAction(List<ParameterValue> parameters) {
        super(parameters);
        this.parameters = parameters;
    }

    public GitHubParametersAction(ParameterValue... parameters) {
        this(Arrays.asList(parameters));
    }

    @Override
    public List<ParameterValue> getParameters() {
        return Collections.unmodifiableList(parameters);
    }

    @Override
    public ParameterValue getParameter(String name) {
        for (ParameterValue parameter : parameters) {
            if (parameter != null && parameter.getName().equals(name)) {
                return parameter;
            }
        }

        return null;
    }

    @Extension
    public static final class GitHubAdditionalParameterEnvironmentContributor extends EnvironmentContributor {

        // See SECURITY-170

        @Override
        @SuppressWarnings("rawtypes")
        public void buildEnvironmentFor(@Nonnull Run run,
                                        @Nonnull EnvVars envs,
                                        @Nonnull TaskListener listener) throws IOException, InterruptedException {

            GitHubParametersAction gpa = run.getAction(GitHubParametersAction.class);
            if (gpa != null) {
                for (ParameterValue p : gpa.getParameters()) {
                    envs.put(p.getName(), String.valueOf(p.getValue()));
                }
            }
            super.buildEnvironmentFor(run, envs, listener);
        }
    }
}

