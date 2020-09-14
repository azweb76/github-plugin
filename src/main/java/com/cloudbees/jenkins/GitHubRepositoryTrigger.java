package com.cloudbees.jenkins;

import com.google.common.base.Preconditions;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
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

import org.jenkinsci.plugins.github.GitHubPlugin;
import org.jenkinsci.plugins.github.admin.GitHubHookRegisterProblemMonitor;
import org.jenkinsci.plugins.github.config.GitHubPluginConfig;
import org.jenkinsci.plugins.github.internal.GHPluginConfigException;
import org.jenkinsci.plugins.github.migration.Migrator;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.Stapler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
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
public class GitHubRepositoryTrigger extends Trigger<Job<?, ?>> implements GitHubTrigger {

    class GitHubRepositoryCheckResult {
        private String repository;
        private String action;
        private String organization;
        private String host;

        public GitHubRepositoryCheckResult() {
        }

        public String getRepository() {
            return repository;
        }

        public void setRepository(String repository) {
            this.repository = repository;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getOrganization() {
            return organization;
        }

        public void setOrganization(String organization) {
            this.organization = organization;
        }

        public String getHost() {
            return this.host;
        }

        public void setHost(String host) {
            this.host = host;
        }
    }

    @DataBoundConstructor
    public GitHubRepositoryTrigger() {
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

    private ArrayList<ParameterValue> getParametersFromCheckResult(GitHubRepositoryCheckResult checkResult) {
        ArrayList<ParameterValue> values = getDefaultParameters();

        values.add(new StringParameterValue("GIT_ORG", checkResult.getOrganization()));
        values.add(new StringParameterValue("GIT_REPO", checkResult.getRepository()));
        values.add(new StringParameterValue("GIT_ACTION", checkResult.getAction()));
        values.add(new StringParameterValue("GIT_HOST", checkResult.getHost()));

        return values;
    }

    /**
     * Called when a POST is made.
     */
    public void onPost(final GitHubTriggerEvent event) {
        getDescriptor().queue.execute(new Runnable() {
            private GitHubRepositoryCheckResult runCheck() {
                try {

                    GHEventPayload.Repository repoPayload = (GHEventPayload.Repository) event.getPayload();
                    GitHubRepositoryCheckResult repoResult = new GitHubRepositoryCheckResult();

                    repoResult.setAction(repoPayload.getAction());
                    repoResult.setHost(repoPayload.getRepository().getUrl().getHost());
                    repoResult.setOrganization(repoPayload.getOrganization().getLogin());
                    repoResult.setRepository(repoPayload.getRepository().getName());

                    return repoResult;

                } catch (Error e) {
                    LOGGER.error("Failed to check repository event", e);
                    throw e;
                } catch (RuntimeException e) {
                    LOGGER.error("Failed to check repository event", e);
                    throw e;
                }
            }

            public void run() {
                GitHubRepositoryCheckResult checkResult = runCheck();
                if (checkResult != null) {
                    LOGGER.info("should trigger using {}, {}", checkResult.getRepository(),
                            checkResult.getOrganization());
                    GitHubRepositoryCause cause = new GitHubRepositoryCause(checkResult.getAction());

                    try {

                        if (asParameterizedJobMixIn(job).scheduleBuild2(0,
                                new CauseAction(cause),
                                new GitHubParametersAction(getParametersFromCheckResult(checkResult))) != null) {
                            LOGGER.info("Repository event detected in " + job.getFullName()
                                    + ". Triggering #" + job.getNextBuildNumber());
                        } else {
                            LOGGER.info("Repository event detected in " + job.getFullName()
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
        return Collections.emptyList();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
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
            return item instanceof Job
                    && item instanceof ParameterizedJobMixIn.ParameterizedJob;
        }

        @Override
        public String getDisplayName() {
            return "Build when a repository event occurs on GitHub";
        }

        /**
         * True if Jenkins should auto-manage hooks.
         *
         * @deprecated Use {@link GitHubPluginConfig#isManageHooks()} instead
         */
        @Deprecated
        public boolean isManageHook() {
            return false;
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

            return FormValidation.ok();
        }
    }

    /**
     * Set to false to prevent the user from overriding the hook URL.
     */
    public static final boolean ALLOW_HOOKURL_OVERRIDE = !Boolean.getBoolean(
            GitHubRepositoryTrigger.class.getName() + ".disableOverride"
    );

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubRepositoryTrigger.class);
}
