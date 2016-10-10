package org.jenkinsci.plugins.github.webhook.subscriber;

import com.cloudbees.jenkins.GitHubPushTrigger;
import com.cloudbees.jenkins.GitHubRepositoryName;
import com.cloudbees.jenkins.GitHubRepositoryTrigger;
import com.cloudbees.jenkins.GitHubTrigger;
import hudson.Extension;
import hudson.model.Job;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.kohsuke.github.GHEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Set;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.jenkinsci.plugins.github.util.JobInfoHelpers.triggerFrom;
import static org.jenkinsci.plugins.github.util.JobInfoHelpers.withTrigger;
import static org.kohsuke.github.GHEvent.REPOSITORY;

/**
 * By default this plugin interested in push events only when job uses {@link GitHubPushTrigger}
 *
 * @author lanwen (Merkushev Kirill)
 * @since 1.12.0
 */
@Extension
@SuppressWarnings("unused")
public class DefaultRepositoryGHEventSubscriber extends GHEventsSubscriber {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultRepositoryGHEventSubscriber.class);

    /**
     * This subscriber is applicable only for job with GHPush trigger
     *
     * @param project to check for trigger
     *
     * @return true if project has {@link GitHubPushTrigger}
     */
    @Override
    protected boolean isApplicable(Job<?, ?> project) {
        return withTrigger(GitHubRepositoryTrigger.class).apply(project);
    }

    /**
     * @return set with only push event
     */
    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(REPOSITORY);
    }

    /**
     * Calls {@link GitHubRepositoryTrigger} in all projects to handle this hook
     *
     * @param event   only PUSH event
     * @param payload payload of gh-event. Never blank
     */
    @Override
    protected void onEvent(final GHEvent event, String payload) {
        final JSONObject json = JSONObject.fromObject(payload);

        LOGGER.info("GHEvent {}", event.name());

        // run in high privilege to see all the projects anonymous users don't see.
        // this is safe because when we actually schedule a build, it's a build that can
        // happen at some random time anyway.
        ACL.impersonate(ACL.SYSTEM, new Runnable() {
            @Override
            public void run() {
                for (Job<?, ?> job : Jenkins.getInstance().getAllItems(Job.class)) {
                    GitHubTrigger trigger = triggerFrom(job, GitHubRepositoryTrigger.class);
                    if (trigger != null) {
                        ArrayList<GitHubRepositoryName> repoNames = getRepositoryNames(job);
                        LOGGER.info("Poked {}", job.getFullDisplayName());
                        trigger.onPost(json, event, null);
                    }
                }
            }
        });

    }
}
