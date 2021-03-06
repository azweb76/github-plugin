package com.cloudbees.jenkins;

import com.google.common.base.Preconditions;
import hudson.Extension;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Project;
import hudson.model.StringParameterValue;
import hudson.triggers.SCMTrigger;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;
import hudson.util.NamingThreadFactory;
import hudson.util.SequentialExecutionQueue;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.scm.api.SCMEvent;
import jenkins.triggers.SCMTriggerItem.SCMTriggerItems;
import org.jenkinsci.plugins.github.GitHubPlugin;
import org.jenkinsci.plugins.github.admin.GitHubHookRegisterProblemMonitor;
import org.jenkinsci.plugins.github.config.GitHubPluginConfig;
import org.jenkinsci.plugins.github.internal.GHPluginConfigException;
import org.jenkinsci.plugins.github.migration.Migrator;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.Stapler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.jenkinsci.plugins.github.util.JobInfoHelpers.asParameterizedJobMixIn;

/**
 * Triggers a build when we receive a GitHub post-commit webhook.
 *
 * @author Kohsuke Kawaguchi
 */
public class GitHubPullRequestTrigger extends Trigger<Job<?, ?>> implements GitHubTrigger {

    class GitHubPullRequestCheckResult {
        private Integer pullId;
        private String headSha;
        private String pullUrl;
        private String committer;
        private String branch;
        private String commit;
        private String comment;

        public GitHubPullRequestCheckResult() {
            this(0, null);
        }

        public GitHubPullRequestCheckResult(Integer pullId, String headSha) {
            this.setPullId(pullId);
            this.setHeadSha(headSha);
        }

        public Integer getPullId() {
            return pullId;
        }

        public void setPullId(Integer prNumber) {
            this.pullId = prNumber;
        }

        public String getHeadSha() {
            return headSha;
        }

        public void setHeadSha(String headSha) {
            this.headSha = headSha;
        }

        public String getPullUrl() {
            return pullUrl;
        }

        public void setPullUrl(String pullUrl) {
            this.pullUrl = pullUrl;
        }

        public String getCommitter() {
            return committer;
        }

        public void setCommitter(String committer) {
            this.committer = committer;
        }

        public String getBranch() {
            return branch;
        }

        public void setBranch(String branch) {
            this.branch = branch;
        }

        public String getCommit() {
            return commit;
        }

        public void setCommit(String commit) {
            this.commit = commit;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }
    }

    @DataBoundConstructor
    public GitHubPullRequestTrigger() {
    }

    /**
     * Called when a POST is made.
     */
    @Deprecated
    public void onPost() {
        onPost(GitHubTriggerEvent.create()
                .build()
        );
    }

    /**
     * Called when a POST is made.
     */
    public void onPost(String triggeredByUser) {
        onPost(GitHubTriggerEvent.create()
                .withOrigin(SCMEvent.originOf(Stapler.getCurrentRequest()))
                .withTriggeredByUser(triggeredByUser)
                .build()
        );
    }


    // protected EnvVars buildEnv(Job<?, ?> job) {
    //     EnvVars env = new EnvVars();
    //     for (EnvironmentContributor contributor : EnvironmentContributor.all()) {
    //         try {
    //             contributor.buildEnvironmentFor(job, env, TaskListener.NULL);
    //         } catch (Exception e) {
    //             LOGGER.debug("{} failed to build env ({}), skipping", contributor.getClass(), e.getMessage(), e);
    //         }
    //     }
    //     return env;
    // }

    private ArrayList<ParameterValue> getDefaultParameters() {
        ArrayList<ParameterValue> values = new ArrayList<>();
        ParametersDefinitionProperty pdp = this.job.getProperty(ParametersDefinitionProperty.class);
        if (pdp != null) {
            for (ParameterDefinition pd : pdp.getParameterDefinitions()) {
                if (pd.getName().equals("sha1")) {
                    continue;
                }
                values.add(pd.getDefaultParameterValue());
            }
        }
        return values;
    }

    private List<ParameterValue> getParametersFromCheckResult(GitHubPullRequestCheckResult checkResult) {
        ArrayList<ParameterValue> values = getDefaultParameters();

        values.add(new StringParameterValue("sha1", checkResult.getHeadSha()));
        values.add(new StringParameterValue("PULL_ID", checkResult.getPullId().toString()));
        values.add(new StringParameterValue("PULL_URL", checkResult.getPullUrl()));
        values.add(new StringParameterValue("GIT_COMMIT", checkResult.getCommit()));
        values.add(new StringParameterValue("GIT_COMMITTER", checkResult.getCommitter()));
        values.add(new StringParameterValue("GIT_BRANCH", checkResult.getBranch()));

        String comment = checkResult.getComment();
        if (comment != null) {
            values.add(new StringParameterValue("PULL_COMMENT", comment));
        }

        return values;
    }

    /**
     * Called when a POST is made.
     */
    public void onPost(final GitHubTriggerEvent event) {
        getDescriptor().queue.execute(new Runnable() {
            private GitHubPullRequestCheckResult runCheck() {
                try {

                    GHEvent eventName = event.getGHEvent();
                    GitHubPullRequestCheckResult prResult = new GitHubPullRequestCheckResult();

                    //SCMTriggerItem item = SCMTriggerItems.asSCMTriggerItem(job);
                    // for (SCM scm : item.getSCMs()) {
                    //     if (GitSCM.class.isInstance(scm)) {
                    //         GitSCM git = (GitSCM) scm;
                    //         for (RemoteConfig rc : git.getRepositories()) {
                    //             for (URIish uri : rc.getURIs()) {
                    //                 LOGGER.debug("GitURI {}, remote name {}", uri, rc.getName());
                    //             }
                    //         }

                    //         for (BranchSpec branchSpec : git.getBranches()) {
                    //             LOGGER.debug("branch name {}", branchSpec.getName());
                    //         }

                    //     }
                    // }

                    String headSha = null;
                    Integer prNumber = 0;

                    if (eventName == GHEvent.PULL_REQUEST) {
                        GHEventPayload.PullRequest prPayload = (GHEventPayload.PullRequest) event.getPayload();
                        String action = prPayload.getAction();
                        if ("opened".equals(action) || "synchronize".equals(action)) {
                            GHPullRequest prObj = prPayload.getPullRequest();
                            prNumber = prObj.getNumber();
                            GHCommitPointer headObj = prObj.getHead();

                            if (prObj.isMerged()) {
                                headSha = "origin/pr/" + prNumber + "/merge";
                            } else {
                                headSha = headObj.getSha();
                            }

                            prResult.setPullId(prNumber);
                            prResult.setPullUrl(prObj.getHtmlUrl().toExternalForm());
                            prResult.setCommitter(prObj.getUser().getLogin());
                            prResult.setBranch(headObj.getRef());
                            prResult.setCommit(headObj.getSha());
                            prResult.setHeadSha(headSha);
                        }
                    } else if (eventName == GHEvent.ISSUE_COMMENT) {

                        GHEventPayload.IssueComment commentPayload = (GHEventPayload.IssueComment) event.getPayload();
                        GitHubRepositoryName changedRepository = event.getRepositoryName();
                        GHRepository ghrepo = changedRepository.resolveOne();

                        if (ghrepo == null) {
                            LOGGER.info("GitHub Server not found in Global Settings. "
                                    + "Skipping issue_comment Pull Request trigger.");
                            return null;
                        }

                        GHIssue prIssue = commentPayload.getIssue();
                        GHIssueComment prComment = commentPayload.getComment();
                        String commentBody = prComment.getBody();

                        if (commentBody.toLowerCase().contains("test this please")) {
                            prNumber = prIssue.getNumber();
                            GHPullRequest pr = ghrepo.getPullRequest(prNumber);
                            if (pr.isMerged()) {
                                headSha = "origin/pr/" + prNumber.toString() + "/merge";
                            } else {
                                headSha = pr.getHead().getSha();
                            }

                            GHCommitPointer head = pr.getHead();

                            prResult.setComment(commentBody);
                            prResult.setPullId(prNumber);
                            prResult.setPullUrl(pr.getHtmlUrl().toString());
                            prResult.setCommitter(pr.getUser().getLogin());
                            prResult.setBranch(head.getRef());
                            prResult.setCommit(head.getSha());
                            prResult.setHeadSha(headSha);
                        } else {
                            LOGGER.debug("commentBody={}", commentBody);
                        }
                    } else {
                        LOGGER.debug("Not triggering pull request (event={})", eventName);
                    }
                    if (prResult.getHeadSha() != null && prResult.getPullId() > 0) {
                        return prResult;
                    }
                } catch (Error e) {
                    LOGGER.error("Failed to check pull request", e);
                    throw e;
                } catch (RuntimeException e) {
                    LOGGER.error("Failed to check pull request", e);
                    throw e;
                } catch (IOException e) {
                    LOGGER.error("Failed to check pull request", e);
                }

                return null;
            }

            public void run() {
                GitHubPullRequestCheckResult checkResult = runCheck();
                if (checkResult != null) {
                    LOGGER.info("should trigger using {}, {}", checkResult.getPullId(), checkResult.getHeadSha());
                    GitHubPullRequestCause cause = new GitHubPullRequestCause(checkResult.getPullId());

                    try {
                        if (asParameterizedJobMixIn(job).scheduleBuild2(0,
                                new CauseAction(cause),
                                new GitHubParametersAction(getParametersFromCheckResult(checkResult))) != null) {
                            LOGGER.info("Pull request detected in " + job.getFullName()
                                    + ". Triggering #" + job.getNextBuildNumber());
                        } else {
                            LOGGER.info("Pull request detected in " + job.getFullName()
                                    + ". Job is already in the queue");
                        }
                    } catch (Exception e) {
                        LOGGER.error("failed to schedule build", e);
                    }
                }
            }
        });
    }

    /**
     * Returns the file that records the last/current polling activity.
     */
    public File getLogFile() {
        return new File(job.getRootDir(), "github-polling.log");
    }

    /**
     * @deprecated Use {@link GitHubRepositoryNameContributor#parseAssociatedNames(AbstractProject)}
     */
    @Deprecated
    public Set<GitHubRepositoryName> getGitHubRepositories() {
        return Collections.emptySet();
    }

    @Override
    public void start(Job<?, ?> project, boolean newInstance) {
        super.start(project, newInstance);
        if (newInstance && GitHubPlugin.configuration().isManageHooks()) {
            registerHooks();
        }
    }

    /**
     * Tries to register hook for current associated job.
     * Do this lazily to avoid blocking the UI thread.
     * Useful for using from groovy scripts.
     *
     * @since 1.11.2
     */
    public void registerHooks() {
        GitHubWebHook.get().registerHookFor(job);
    }

    @Override
    public void stop() {
        if (job == null) {
            return;
        }

        if (GitHubPlugin.configuration().isManageHooks()) {
            Cleaner cleaner = Cleaner.get();
            if (cleaner != null) {
                cleaner.onStop(job);
            }
        }
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        if (job == null) {
            return Collections.emptyList();
        }

        return Collections.singleton(new GitHubWebHookPollingAction());
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Action object for {@link Project}. Used to display the polling log.
     */
    public final class GitHubWebHookPollingAction implements Action {
        public Job<?, ?> getOwner() {
            return job;
        }

        public String getIconFileName() {
            return "clipboard.png";
        }

        public String getDisplayName() {
            return "GitHub Hook Log";
        }

        public String getUrlName() {
            return "GitHubPollLog";
        }

        public String getLog() throws IOException {
            return Util.loadFile(getLogFile());
        }
    }

    @Extension
    public static class DescriptorImpl extends TriggerDescriptor {
        private final transient SequentialExecutionQueue queue =
                new SequentialExecutionQueue(Executors.newSingleThreadExecutor(threadFactory()));

        private transient String hookUrl;

        private transient List<Credential> credentials;

        @Inject
        private transient GitHubHookRegisterProblemMonitor monitor;

        @Inject
        private transient SCMTrigger.DescriptorImpl scmTrigger;

        private transient int maximumThreads = Integer.MIN_VALUE;

        public DescriptorImpl() {
            checkThreadPoolSizeAndUpdateIfNecessary();
        }

        /**
         * Update the {@link java.util.concurrent.ExecutorService} instance.
         */
        /*package*/
        synchronized void checkThreadPoolSizeAndUpdateIfNecessary() {
            if (scmTrigger != null) {
                int count = scmTrigger.getPollingThreadCount();
                if (maximumThreads != count) {
                    maximumThreads = count;
                    queue.setExecutors(
                            (count == 0
                                    ? Executors.newCachedThreadPool(threadFactory())
                                    : Executors.newFixedThreadPool(maximumThreads, threadFactory())));
                }
            }
        }

        @Override
        public boolean isApplicable(Item item) {
            return item instanceof Job && SCMTriggerItems.asSCMTriggerItem(item) != null
                    && item instanceof ParameterizedJobMixIn.ParameterizedJob;
        }

        @Override
        public String getDisplayName() {
            return "Build when a Pull Request is submitted to GitHub";
        }

        /**
         * True if Jenkins should auto-manage hooks.
         *
         * @deprecated Use {@link GitHubPluginConfig#isManageHooks()} instead
         */
        @Deprecated
        public boolean isManageHook() {
            return GitHubPlugin.configuration().isManageHooks();
        }

        /**
         * Returns the URL that GitHub should post.
         *
         * @deprecated use {@link GitHubPluginConfig#getHookUrl()} instead
         */
        @Deprecated
        public URL getHookUrl() throws GHPluginConfigException {
            return GitHubPlugin.configuration().getHookUrl();
        }

        /**
         * @return null after migration
         * @deprecated use {@link GitHubPluginConfig#getConfigs()} instead.
         */
        @Deprecated
        public List<Credential> getCredentials() {
            return credentials;
        }

        /**
         * Used only for migration
         *
         * @return null after migration
         * @deprecated use {@link GitHubPluginConfig#getHookUrl()}
         */
        @Deprecated
        public URL getDeprecatedHookUrl() {
            if (isEmpty(hookUrl)) {
                return null;
            }
            try {
                return new URL(hookUrl);
            } catch (MalformedURLException e) {
                LOGGER.warn("Malformed hook url skipped while migration ({})", e.getMessage());
                return null;
            }
        }

        /**
         * Used to cleanup after migration
         */
        public void clearDeprecatedHookUrl() {
            this.hookUrl = null;
        }

        /**
         * Used to cleanup after migration
         */
        public void clearCredentials() {
            this.credentials = null;
        }

        /**
         * @deprecated use {@link GitHubPluginConfig#isOverrideHookURL()}
         */
        @Deprecated
        public boolean hasOverrideURL() {
            return GitHubPlugin.configuration().isOverrideHookURL();
        }

        /**
         * Uses global xstream to enable migration alias used in
         * {@link Migrator#enableCompatibilityAliases()}
         */
        @Override
        protected XmlFile getConfigFile() {
            return new XmlFile(Jenkins.XSTREAM2, super.getConfigFile().getFile());
        }

        public static DescriptorImpl get() {
            return Trigger.all().get(DescriptorImpl.class);
        }

        public static boolean allowsHookUrlOverride() {
            return ALLOW_HOOKURL_OVERRIDE;
        }

        private static ThreadFactory threadFactory() {
            return new NamingThreadFactory(Executors.defaultThreadFactory(), "GitHubPullRequestTrigger");
        }

        /**
         * Checks that repo defined in this job is not in administrative monitor as failed to be registered.
         * If that so, shows warning with some instructions
         *
         * @param job - to check against. Should be not null and have at least one repo defined
         *
         * @return warning or empty string
         * @since TODO
         */
        @SuppressWarnings("unused")
        public FormValidation doCheckHookRegistered(@AncestorInPath Job<?, ?> job) {
            Preconditions.checkNotNull(job, "Job can't be null if wants to check hook in monitor");

            Collection<GitHubRepositoryName> repos = GitHubRepositoryNameContributor.parseAssociatedNames(job);

            for (GitHubRepositoryName repo : repos) {
                if (monitor.isProblemWith(repo)) {
                    return FormValidation.warning(
                            org.jenkinsci.plugins.github.Messages.github_trigger_check_method_warning_details(
                                    repo.getUserName(), repo.getRepositoryName(), repo.getHost()
                            ));
                }
            }

            return FormValidation.ok();
        }
    }

    /**
     * Set to false to prevent the user from overriding the hook URL.
     */
    public static final boolean ALLOW_HOOKURL_OVERRIDE = !Boolean.getBoolean(
            GitHubPullRequestTrigger.class.getName() + ".disableOverride"
    );

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubPullRequestTrigger.class);
}
