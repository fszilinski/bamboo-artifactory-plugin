package org.jfrog.bamboo.release.scm.git;

import com.atlassian.bamboo.build.logger.BuildLogger;
import com.atlassian.bamboo.credentials.CredentialsAccessor;
import com.atlassian.bamboo.credentials.CredentialsData;
import com.atlassian.bamboo.credentials.PrivateKeyCredentials;
import com.atlassian.bamboo.credentials.SshCredentialsImpl;
import com.atlassian.bamboo.security.EncryptionService;
import com.atlassian.bamboo.v2.build.BuildContext;
import com.atlassian.bamboo.variable.CustomVariableContext;
import com.atlassian.bamboo.vcs.configuration.PlanRepositoryDefinition;
import com.atlassian.spring.container.ContainerManager;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.opensymphony.xwork.TextProvider;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.util.FS;
import org.jetbrains.annotations.Nullable;
import org.jfrog.bamboo.release.scm.AbstractScmManager;
import org.jfrog.bamboo.util.version.ScmHelper;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;

/**
 * Manager that manages the Git repository.
 *
 * @author Tomer Cohen
 */
public class GitManager extends AbstractScmManager {
    private static final Logger log = Logger.getLogger(GitManager.class);
    private static final String REF_PREFIX = "refs/heads/";
    private static final String REFS_TAGS = "refs/tags/";
    private EncryptionService encryptionService = (EncryptionService) ContainerManager.getComponent("encryptionService");
    private BuildLogger buildLogger;
    private TextProvider textProvider;
    private CustomVariableContext customVariableContext;
    private CredentialsAccessor credentialsAccessor;
    private String username = "";
    private String password = "";

    public GitManager(BuildContext context, PlanRepositoryDefinition repository, BuildLogger buildLogger,
                      CustomVariableContext customVariableContext, CredentialsAccessor credentialsAccessor) {
        super(context, repository, buildLogger);
        this.buildLogger = buildLogger;
        this.customVariableContext = customVariableContext;
        this.credentialsAccessor = credentialsAccessor;
        if (ScmHelper.isGit(repository)) {
            username = configuration.getString("repository.git.username", "");
            password = encryptionService.decrypt(configuration.getString("repository.git.password", ""));
        } else if (ScmHelper.isGithub(repository)) {
            username = configuration.getString("repository.github.username", "");
            password = encryptionService.decrypt(configuration.getString("repository.github.password", ""));
        }
    }

    @Override
    public void commitWorkingCopy(String commitMessage) throws IOException, InterruptedException {
        try (Git git = createGitApi()) {
            git.commit().setMessage(commitMessage).setAll(true).setCommitter(new PersonIdent(git.getRepository())).call();
        } catch (Exception e) {
            String message = "An error " + e.getMessage() + " occurred while commiting the working copy";
            log.error(buildLogger.addErrorLogEntry("[RELEASE]" + message));
            throw new IOException(message, e);
        }
    }


    @Override
    public void createTag(String tagUrl, String commitMessage) throws IOException, InterruptedException {
        try (Git git = createGitApi()) {
            git.tag().setMessage(commitMessage).setName(tagUrl).call();
        } catch (Exception e) {
            String message = "An error " + e.getMessage() + " occurred while creating a tag " + tagUrl;
            log.error(buildLogger.addErrorLogEntry("[RELEASE]" + message));
            throw new IOException("An error occurred while creating a tag", e);
        }
    }

    @Override
    public String getRemoteUrl() {
        if (ScmHelper.isGit(repository)) {
            return customVariableContext.substituteString(configuration.getString("repository.git.repositoryUrl"));
        } else if (ScmHelper.isGithub(repository)) {
            String repository = customVariableContext.substituteString(configuration.getString("repository.github.repository"));
            return "https://github.com/" + repository + ".git";
            //Stash repository url
        } else if (ScmHelper.isStash(repository)) {
            return configuration.getString("repository.stash.repositoryUrl");
        }

        return "";
    }

    public String getCurrentCommitHash() throws IOException {
        File workingDir = getGitDir();
        FileRepository localRepository = null;
        try {
            localRepository = new FileRepository(workingDir);
            ObjectId objId = localRepository.resolve(Constants.HEAD);
            return (objId != null ? objId.getName() : null);
        } catch (IOException e) {
            log.warn(buildLogger
                    .addBuildLogEntry(textProvider.getText("repository.git.messages.cannotDetermineRevision", Arrays
                            .asList(workingDir)) + " " + e.getMessage()), e);
            return null;
        } finally {
            if (localRepository != null) {
                localRepository.close();
            }
        }
    }

    public String getCurrentBranch() throws IOException {
        File workingDir = getGitDir();
        FileRepository localRepository = null;
        try {
            localRepository = new FileRepository(workingDir);
            return localRepository.getBranch();
        } catch (IOException e) {
            log.warn(buildLogger
                    .addBuildLogEntry(textProvider.getText("repository.git.messages.cannotDetermineRevision", Arrays
                            .asList(workingDir)) + " " + e.getMessage()), e);
            return null;
        } finally {
            if (localRepository != null) {
                localRepository.close();
            }
        }
    }

    public void setBuildLogger(BuildLogger buildLogger) {
        this.buildLogger = buildLogger;
    }

    public void setTextProvider(TextProvider textProvider) {
        this.textProvider = textProvider;
    }

    public void checkoutBranch(final String branch, final boolean create) throws IOException {
        log("Checking out branch: " + branch);
        try (Git git = createGitApi()) {
            git.checkout().setCreateBranch(create).setForce(create).setName(branch).call();
        } catch (Exception e) {
            String message = "An error '" + e.getMessage() + "' occurred while checking out branch: " + branch;
            log.error(buildLogger.addErrorLogEntry(message));
            throw new IOException(message, e);
        }
    }

    public void push(String url, String branch) throws IOException {
        try (Git git = createGitApi()) {
            PushCommand pushCommand = buildPushCommand(git);
            pushCommand.setRefSpecs(new RefSpec(REF_PREFIX + branch)).setRemote(url);
            Iterable<PushResult> result;
            log("Pushing branch: " + branch + " to url: " + url);
            try {
                result = pushCommand.call();
            } catch (Exception ire) {
                String message =
                        "An error '" + ire.getMessage() + "' occurred while pushing branch: " + branch + " to url: " + url;
                log.error(buildLogger.addErrorLogEntry("[RELEASE] " + message));
                throw new IOException(message, ire);
            }
            for (PushResult pushResult : result) {
                if (StringUtils.isNotBlank(pushResult.getMessages())) {
                    log(pushResult.getMessages());

                }
            }
        }
    }

    public void pushTag(String remoteUrl, String tagUrl) throws IOException {
        String escapedTagName = tagUrl.replace(' ', '_');
        Iterable<PushResult> result;
        try (Git git = createGitApi()) {
            PushCommand pushCommand = buildPushCommand(git);
            pushCommand.setRefSpecs(new RefSpec(REFS_TAGS + escapedTagName)).setRemote(remoteUrl);
            log("Pushing tag: " + escapedTagName + " to url " + remoteUrl);
            try {
                result = pushCommand.call();
            } catch (Exception ire) {
                String message =
                        "An error '" + ire.getMessage() + "' occurred while pushing tag: " + tagUrl + " to:" + remoteUrl;
                log.error(buildLogger.addErrorLogEntry("[RELEASE] " + message));
                throw new IOException(message, ire);
            }
            for (PushResult pushResult : result) {
                if (StringUtils.isNotBlank(pushResult.getMessages())) {
                    log(pushResult.getMessages());
                }
            }
        }
    }

    public void deleteLocalBranch(String branch) throws IOException {
        try (Git git = createGitApi()) {
            log("Deleting local branch: " + branch);
            git.branchDelete().setBranchNames(branch).setForce(true).call();
        } catch (Exception e) {
            String message = "An error '" + e.getMessage() + "' occurred while deleting local branch: " + branch;
            log.error(buildLogger.addErrorLogEntry("[RELEASE] " + message));
            throw new IOException(message, e);
        }
    }

    public void deleteRemoteBranch(String repository, String branch) throws IOException {
        Iterable<PushResult> results;
        try (Git git = createGitApi()) {
            PushCommand pushCommand = buildPushCommand(git);
            pushCommand.setRefSpecs(new RefSpec(":" + REF_PREFIX + branch)).setRemote(repository);
            log("Deleting remote branch: " + branch + " from " + repository);
            try {
                results = pushCommand.call();
            } catch (Exception e) {
                String message = "An error '" + e.getMessage() + "' occurred while deleting remote branch: " + branch +
                        " from remote: " + repository;
                log.error(buildLogger.addErrorLogEntry("[RELEASE] " + message));
                throw new IOException(message, e);
            }
            for (PushResult result : results) {
                if (StringUtils.isNotBlank(result.getMessages())) {
                    log(result.getMessages());
                }
            }
        }
    }

    public void deleteLocalTag(String tag) throws IOException {
        try (Git git = createGitApi()) {
            DeleteTagCommand deleteTagCommand = new DeleteTagCommand(git.getRepository());
            deleteTagCommand.setName(tag);
            log("Deleting local tag: " + tag);
            try {
                RefUpdate.Result result = deleteTagCommand.call();
                log.debug("Result of deletion of local tag: " + result);
            } catch (Exception e) {
                String message = "An error '" + e.getMessage() + "' occurred when deleting local tag: " + tag;
                log.error(buildLogger.addErrorLogEntry("[RELEASE] " + message));
                throw new IOException(message, e);
            }
        }
    }

    public void revertWorkingCopy(String ish) throws GitAPIException, IOException {
        log("Reverting local copy to: " + ish);
        try (Git git = createGitApi()) {
            org.eclipse.jgit.lib.Repository repository = git.getRepository();
            ResetCommand resetCommand = new ResetCommand(repository);
            resetCommand.setMode(ResetCommand.ResetType.HARD).setRef(ish).call();
        }
    }

    public void deleteRemoteTag(String repository, String tag) throws IOException {
        try (Git git = createGitApi()) {
            PushCommand pushCommand = buildPushCommand(git);
            pushCommand.setRefSpecs(new RefSpec(":" + REFS_TAGS + tag)).setRemote(repository);
            Iterable<PushResult> results;
            log("Deleting remote tag: " + tag + " from " + repository);
            try {
                results = pushCommand.call();
            } catch (Exception e) {
                String message =
                        "An error '" + e.getMessage() + "' occurred when deleting remote tag: " + tag + " from remote: " +
                                repository;
                log.error(buildLogger.addErrorLogEntry("[RELEASE]" + message));
                throw new IOException(message, e);
            }
            for (PushResult result : results) {
                if (StringUtils.isNotBlank(result.getMessages())) {
                    log(result.getMessages());
                }
            }
        }
    }

    private PushCommand buildPushCommand(Git git) throws IOException {
        GitAuthenticationType authType = getAuthType();
        PushCommand pushCommand;
        if (authType == GitAuthenticationType.PASSWORD || authType == GitAuthenticationType.NONE) {
            pushCommand = git.push();
            pushCommand.setForce(true);
            UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(username, password);
            pushCommand.setCredentialsProvider(provider);
        } else {
            Transport transport = createTransport();
            pushCommand = new SshPushCommand(git.getRepository(), transport);
        }
        return pushCommand;
    }

    private Git createGitApi() throws IOException {
        File workingDir = getGitDir();
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        builder.setGitDir(workingDir);
        return new Git(builder.setup().build());
    }

    private File getGitDir() {
        File gitDirectory = new File(getAndValidateCheckoutDirectory(), Constants.DOT_GIT);
        if (!gitDirectory.exists()) {
            throw new IllegalStateException("Git Dir: " + gitDirectory.getAbsolutePath() + " Does not exist");
        }
        return gitDirectory;
    }

    private Transport createTransport() throws IOException {
        String url = getRemoteUrl();
        Transport transport = null;
        try (Git git = createGitApi()) {
            GitAuthenticationType authenticationType = getAuthType();
            if (authenticationType.equals(GitAuthenticationType.SSH_KEYPAIR)
                    || authenticationType.equals(GitAuthenticationType.SHARED_CREDENTIALS)) {
                transport = Transport.open(git.getRepository(), url);
                if (transport instanceof SshTransport) {
                    String sshKey = "";
                    String passphrase = "";
                    if (authenticationType.equals(GitAuthenticationType.SHARED_CREDENTIALS)) {
                        Long sharedCredentialsId = configuration.getLong("repository.git.sharedCrendentials", null);
                        final CredentialsData credentials = credentialsAccessor.getCredentials(sharedCredentialsId);
                        if (credentials != null) {
                            final PrivateKeyCredentials sshCredentials = new SshCredentialsImpl(credentials);
                            sshKey = encryptionService.decrypt(sshCredentials.getKey());
                            passphrase = encryptionService.decrypt(sshCredentials.getPassphrase());
                        } else {
                            sshKey = "";
                            passphrase = "";
                        }
                    } else {
                        //Stash ssh private key
                        if (ScmHelper.isStash(repository)) {
                            sshKey = encryptionService.decrypt(configuration.getString("repository.stash.key.private"));
                        } else {
                            sshKey = encryptionService.decrypt(configuration.getString("repository.git.ssh.key", ""));
                            passphrase = encryptionService.decrypt(configuration.getString("repository.git.ssh.passphrase", ""));
                        }
                    }
                    SshSessionFactory factory = new GitSshSessionFactory(sshKey, passphrase);
                    ((SshTransport) transport).setSshSessionFactory(factory);
                }
            }
        } catch (URISyntaxException e) {
            e.printStackTrace();
        } finally {
            if (transport != null) {
                transport.close();
            }
        }
        return transport;
    }

    private GitAuthenticationType getAuthType() {
        if (ScmHelper.isGithub(repository)) {
            return GitAuthenticationType.PASSWORD;
        }
        //Stash work with ssh Authentication, when using the "Application Links" concept.
        if (ScmHelper.isStash(repository)) {
            return GitAuthenticationType.SSH_KEYPAIR;
        }
        String authentication = configuration.getString("repository.git.authenticationType", "");
        return GitAuthenticationType.valueOf(authentication);
    }


    private static class GitSshSessionFactory extends JschConfigSessionFactory {

        final private String key;
        final private String passphrase;

        GitSshSessionFactory(@Nullable final String key, @Nullable final String passphrase) {
            this.key = key;
            this.passphrase = passphrase;
        }

        @Override
        protected void configure(OpenSshConfig.Host hc, Session session) {
            session.setConfig("StrictHostKeyChecking", "no");
        }

        @Override
        protected JSch getJSch(final OpenSshConfig.Host hc, FS fs) throws JSchException {
            JSch jsch = super.getJSch(hc, fs);
            jsch.removeAllIdentity();
            if (StringUtils.isNotEmpty(key)) {
                jsch.addIdentity("identityName", key.getBytes(), null, passphrase.getBytes());
            }
            return jsch;
        }
    }
}
