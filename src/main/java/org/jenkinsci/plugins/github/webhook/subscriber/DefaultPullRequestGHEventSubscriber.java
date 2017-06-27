package org.jenkinsci.plugins.github.webhook.subscriber;

import com.cloudbees.jenkins.GitHubPullRequestTrigger;
import com.cloudbees.jenkins.GitHubPushTrigger;
import com.cloudbees.jenkins.GitHubRepositoryName;
import com.cloudbees.jenkins.GitHubRepositoryNameContributor;
import com.cloudbees.jenkins.GitHubTriggerEvent;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.github.extension.GHSubscriberEvent;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.util.Set;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.jenkinsci.plugins.github.util.JobInfoHelpers.triggerFrom;
import static org.jenkinsci.plugins.github.util.JobInfoHelpers.withTrigger;
import static org.kohsuke.github.GHEvent.ISSUE_COMMENT;
import static org.kohsuke.github.GHEvent.PULL_REQUEST;

/**
 * By default this plugin interested in push events only when job uses {@link GitHubPushTrigger}
 *
 * @author lanwen (Merkushev Kirill)
 * @since 1.12.0
 */
@Extension
@SuppressWarnings("unused")
public class DefaultPullRequestGHEventSubscriber extends GHEventsSubscriber {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPullRequestGHEventSubscriber.class);

    /**
     * This subscriber is applicable only for job with GHPush trigger
     *
     * @param project to check for trigger
     *
     * @return true if project has {@link GitHubPushTrigger}
     */
    @Override
    protected boolean isApplicable(Item project) {
        return withTrigger(GitHubPullRequestTrigger.class).apply(project);
    }

    /**
     * @return set with only push event
     */
    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(PULL_REQUEST, ISSUE_COMMENT);
    }

    /**
     * Calls {@link GitHubPullRequestTrigger} in all projects to handle this hook
     *
     * @param event   only PUSH event
     * @param payload payload of gh-event. Never blank
     */
    @Override
    protected void onEvent(final GHSubscriberEvent event) {
        final GHEventPayload payload;
        final URL repoUrl;
        final String userLogin;
        try {
            if (event.getGHEvent() == GHEvent.PULL_REQUEST) {
                GHEventPayload.PullRequest prPayload = GitHub.offline().parseEventPayload(
                    new StringReader(event.getPayload()), GHEventPayload.PullRequest.class);
                repoUrl = prPayload.getRepository().getUrl();
                userLogin = prPayload.getPullRequest().getUser().getLogin();
                payload = prPayload;
            } else if (event.getGHEvent() == GHEvent.ISSUE_COMMENT) {
                GHEventPayload.IssueComment issueCommentPayload = GitHub.offline().parseEventPayload(
                    new StringReader(event.getPayload()), GHEventPayload.IssueComment.class);
                repoUrl = issueCommentPayload.getRepository().getUrl();
                userLogin = issueCommentPayload.getSender().getLogin();
                payload = issueCommentPayload;
            } else {
                LOGGER.info("GHEvent {} not supported. Skipping.", event.getGHEvent());
                return;
            }
        } catch (IOException e) {
            LOGGER.warn("Received malformed PushEvent: " + event.getPayload(), e);
            return;
        }

        LOGGER.info("Received PullRequestEvent for {} from {}", repoUrl, event.getOrigin());
        final GitHubRepositoryName changedRepository = GitHubRepositoryName.create(repoUrl.toExternalForm());

        if (changedRepository != null) {
            // run in high privilege to see all the projects anonymous users don't see.
            // this is safe because when we actually schedule a build, it's a build that can
            // happen at some random time anyway.
            ACL.impersonate(ACL.SYSTEM, new Runnable() {
                @Override
                public void run() {
                    for (Job<?, ?> job : Jenkins.getInstance().getAllItems(Job.class)) {
                        GitHubPullRequestTrigger trigger = triggerFrom(job, GitHubPullRequestTrigger.class);
                        if (trigger != null) {
                            LOGGER.debug("Considering to poke {}", job.getFullDisplayName());
                            if (GitHubRepositoryNameContributor.parseAssociatedNames(job)
                                    .contains(changedRepository)) {
                                LOGGER.info("Poked {}", job.getFullDisplayName());
                                trigger.onPost(GitHubTriggerEvent.create()
                                    .withRepositoryName(changedRepository)
                                    .withTimestamp(event.getTimestamp())
                                    .withOrigin(event.getOrigin())
                                    .withTriggeredByUser(userLogin)
                                    .withPayload(payload)
                                    .withGHEvent(event.getGHEvent())
                                    .build()
                                );
                            }
                        }
                    }
                }
            });
        } else {
            LOGGER.warn("Malformed repo url {}", repoUrl);
        }
    }
}
