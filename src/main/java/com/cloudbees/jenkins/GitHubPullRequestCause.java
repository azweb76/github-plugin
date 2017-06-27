package com.cloudbees.jenkins;

import hudson.triggers.SCMTrigger.SCMTriggerCause;

import java.io.File;
import java.io.IOException;

import static java.lang.String.format;

/**
 * UI object that says a build is started by GitHub post-commit hook.
 *
 * @author Kohsuke Kawaguchi
 */
public class GitHubPullRequestCause extends SCMTriggerCause {
    /**
     * The name of the user who pushed to GitHub.
     */
    private Integer prNumber;

    public GitHubPullRequestCause(Integer prNumber) {
        this("", prNumber);
    }

    public GitHubPullRequestCause(String pollingLog, Integer prNumber) {
        super(pollingLog);
        this.prNumber = prNumber;
    }

    public GitHubPullRequestCause(File pollingLog, Integer prNumber) throws IOException {
        super(pollingLog);
        this.prNumber = prNumber;
    }

    @Override
    public String getShortDescription() {
        return format("Started by GitHub pull request #%s", prNumber.toString());
    }
}

