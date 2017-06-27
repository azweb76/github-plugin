package com.cloudbees.jenkins;

import hudson.triggers.SCMTrigger.SCMTriggerCause;

import java.io.File;
import java.io.IOException;

import static java.lang.String.format;

/**
 * UI object that says a build is started by GitHub organization repository hook.
 *
 * @author Kohsuke Kawaguchi
 */
public class GitHubRepositoryCause extends SCMTriggerCause {
    /**
     * The name of the user who pushed to GitHub.
     */
    private String action;

    public GitHubRepositoryCause(String action) {
        this("", action);
    }

    public GitHubRepositoryCause(String pollingLog, String action) {
        super(pollingLog);
        this.action = action;
    }

    public GitHubRepositoryCause(File pollingLog, Integer prNumber) throws IOException {
        super(pollingLog);
        this.action = action;
    }

    @Override
    public String getShortDescription() {
        return format("Started by GitHub repository event %s", action);
    }
}

