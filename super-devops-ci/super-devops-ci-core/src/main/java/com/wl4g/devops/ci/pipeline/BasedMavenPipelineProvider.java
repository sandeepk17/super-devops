/*
 * Copyright 2017 ~ 2025 the original author or authors. <wanglsir@gmail.com, 983708408@qq.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wl4g.devops.ci.pipeline;

import com.wl4g.devops.ci.pipeline.model.PipelineInfo;
import com.wl4g.devops.ci.utils.GitUtils;
import com.wl4g.devops.ci.utils.SSHTool;
import com.wl4g.devops.common.bean.ci.*;
import com.wl4g.devops.common.bean.ci.dto.TaskResult;
import com.wl4g.devops.common.exception.ci.LockStateException;
import com.wl4g.devops.common.utils.DateUtils;
import com.wl4g.devops.common.utils.codec.AES;
import com.wl4g.devops.common.utils.io.FileIOUtils;
import com.wl4g.devops.shell.utils.ShellContextHolder;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.io.File;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import static com.wl4g.devops.common.constants.CiDevOpsConstants.*;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Abstract based deploy provider.
 *
 * @author Wangl.sir <983708408@qq.com>
 * @author vjay
 * @date 2019-05-05 17:17:00
 */
public abstract class BasedMavenPipelineProvider extends AbstractPipelineProvider {
	final protected Logger log = LoggerFactory.getLogger(getClass());

	public BasedMavenPipelineProvider(PipelineInfo info) {
		super(info);
	}

	/**
	 * Execute
	 */
	public abstract void execute() throws Exception;

	/**
	 * Scp + tar + move to basePath
	 */
	public String scpAndTar(String path, String targetHost, String userName, String targetPath, String rsa) throws Exception {
		String result = mkdirs(targetHost, userName, "/home/" + userName + "/tmp", rsa) + "\n";
		// scp
		result += scpToTmp(path, targetHost, userName, rsa) + "\n";
		// tar
		result += tarToTmp(targetHost, userName, path, rsa) + "\n";
		// remove
		result += removeTarPath(targetHost, userName, path, targetPath, rsa);
		// move
		result += moveToTarPath(targetHost, userName, path, targetPath, rsa) + "\n";
		return result;
	}

	/**
	 * Scp To Tmp
	 */
	public String scpToTmp(String path, String targetHost, String userName, String rsa) throws Exception {
		String rsaKey = config.getTranform().getCipherKey();
		AES aes = new AES(rsaKey);
		char[] rsaReal = aes.decrypt(rsa).toCharArray();
		return SSHTool.uploadFile(targetHost, userName, rsaReal, new File(path), "/home/" + userName + "/tmp");
	}

	/**
	 * Unzip in tmp
	 */
	public String tarToTmp(String targetHost, String userName, String path, String rsa) throws Exception {
		String command = "tar -xvf /home/" + userName + "/tmp" + "/" + subPackname(path) + " -C /home/" + userName + "/tmp";
		return exceCommand(targetHost, userName, command, rsa);
	}

	/**
	 * remove tar path
	 */
	public String removeTarPath(String targetHost, String userName, String path, String targetPath, String rsa) throws Exception {
		String s = targetPath + "/" + subPacknameWithOutPostfix(path);
		if (StringUtils.isBlank(s) || s.trim().equals("/")) {
			throw new RuntimeException("bad command");
		}
		String command = "rm -Rf " + targetPath + "/" + subPacknameWithOutPostfix(path);
		return exceCommand(targetHost, userName, command, rsa);
	}

	/**
	 * Move to tar path
	 */
	public String moveToTarPath(String targetHost, String userName, String path, String targetPath, String rsa) throws Exception {
		String command = "mv /home/" + userName + "/tmp" + "/" + subPacknameWithOutPostfix(path) + " " + targetPath + "/"
				+ subPacknameWithOutPostfix(path);
		return exceCommand(targetHost, userName, command, rsa);
	}

	/**
	 * Local back up
	 */
	public String backupLocal(String path, String alias, String branchName) throws Exception {
		File baseDir = config.getJobBaseDir(getPipelineInfo().getTaskHistory().getId());
		checkPath(baseDir.getAbsolutePath());
		String command = "cp -Rf " + path + " " + baseDir.getAbsolutePath() + "/" + subPackname(path);
		return SSHTool.exec(command);
	}

	/**
	 * Get local back up , for rollback
	 */
	public String getBackupLocal(String backFile, String target) throws Exception {
		File baseDir = config.getJobBaseDir(getPipelineInfo().getTaskHistory().getId());
		checkPath(baseDir.getAbsolutePath());
		String command = "cp -Rf " + backFile + " " + target;
		return SSHTool.exec(command);
	}

	/**
	 * Get date to string user for version
	 */
	public String getDateTimeStr() {
		String str = DateUtils.formatDate(new Date(), DateUtils.YMDHM);
		str = str.substring(2);
		str = "-v" + str;
		return str;
	}

	/**
	 * Get Package Name from path
	 */
	public String subPackname(String path) {
		String[] a = path.split("/");
		return a[a.length - 1];
	}

	/**
	 * Get Packname WithOut Postfix from path
	 */
	public String subPacknameWithOutPostfix(String path) {
		String a = subPackname(path);
		return a.substring(0, a.lastIndexOf("."));
	}

	public String replaceMaster(String str) {
		return str.replaceAll("master-", "");
	}

	public void checkPath(String path) {
		File file = new File(path);
		if (!file.exists()) {
			file.mkdirs();
		}
	}

	/**
	 * build
	 *
	 * @param taskHistory
	 * @param taskResult
	 * @throws Exception
	 */
	public void build(TaskHistory taskHistory, TaskResult taskResult, boolean isRollback) throws Exception {
		File jobLog = config.getJobLog(taskHistory.getId());
		if (log.isInfoEnabled()) {
			log.info("Building started, stdout to {}", jobLog.getAbsolutePath());
		}
		// Mark start EOF.
		FileIOUtils.writeFile(jobLog, LOG_FILE_START);

		// Dependencies.
		LinkedHashSet<Dependency> dependencys = dependencyService.getHierarchyDependencys(taskHistory.getProjectId(), null);
		if (log.isInfoEnabled()) {
			log.info("Building Analysis dependencys={}", dependencys);
		}

		// Custom dependencies commands.
		List<TaskBuildCommand> commands = taskHisBuildCommandDao.selectByTaskHisId(taskHistory.getId());
		for (Dependency depd : dependencys) {
			String depCmd = extractDependencyBuildCommand(commands, depd.getProjectId());
			doBuilding(taskHistory, depd.getDependentId(), depd.getDependentId(), depd.getBranch(), taskResult, true, isRollback,
					depCmd);
			// Is Continue ? if fail then return
			if (!taskResult.isSuccess()) {
				return;
			}
		}
		doBuilding(taskHistory, taskHistory.getProjectId(), null, taskHistory.getBranchName(), taskResult, false, isRollback,
				taskHistory.getBuildCommand());

		// Mark end EOF.
		FileIOUtils.writeFile(jobLog, LOG_FILE_END);
	}

	/**
	 * Extract dependency project custom command.
	 * 
	 * @param commands
	 * @param projectId
	 * @return
	 */
	private String extractDependencyBuildCommand(List<TaskBuildCommand> commands, Integer projectId) {
		Assert.notEmpty(commands, "taskBuildCommands is empty");
		Assert.notNull(projectId, "projectId is null");
		for (TaskBuildCommand taskBuildCommand : commands) {
			if (taskBuildCommand.getProjectId().intValue() == projectId.intValue()) {
				return taskBuildCommand.getCommand();
			}
		}
		return null;
	}

	/**
	 * Execution building dependency project.
	 * 
	 * @param taskHistory
	 * @param projectId
	 * @param dependencyId
	 * @param branch
	 * @param taskResult
	 * @param isDependency
	 * @param isRollback
	 * @param buildCommand
	 * @throws Exception
	 */
	private void doBuilding(TaskHistory taskHistory, Integer projectId, Integer dependencyId, String branch,
			TaskResult taskResult, boolean isDependency, boolean isRollback, String buildCommand) throws Exception {
		Lock lock = lockManager.getLock(CI_LOCK + projectId, config.getJob().getSharedDependencyTryTimeoutMs(), TimeUnit.MINUTES);
		if (lock.tryLock()) { // Dependency build idle?
			try {
				doGetSourceAndMvnBuild(taskHistory, projectId, dependencyId, branch, taskResult, isDependency, isRollback,
						buildCommand);
			} finally {
				lock.unlock();
			}
		} else {
			if (log.isInfoEnabled()) {
				log.info("Waiting to build dependency, timeout for {}sec ...", config.getJob().getJobTimeout());
			}
			try {
				long begin = System.currentTimeMillis();
				// Waiting for other job builds to completed.
				if (lock.tryLock(config.getJob().getSharedDependencyTryTimeoutMs(), TimeUnit.SECONDS)) {
					if (log.isInfoEnabled()) {
						long cost = System.currentTimeMillis() - begin;
						log.info("Wait for dependency build to be skipped successfully! cost: {}ms", cost);
					}
				} else {
					throw new LockStateException("Failed to build, timeout waiting for dependency building.");
				}
			} finally {
				lock.unlock();
			}
		}
	}

	/**
	 * Execution pipeline core to get source and MVN building.
	 * 
	 * @param taskHistory
	 * @param projectId
	 * @param dependencyId
	 * @param branch
	 * @param taskRet
	 * @param isDependency
	 * @param isRollback
	 * @param buildCommand
	 * @throws Exception
	 */
	private void doGetSourceAndMvnBuild(TaskHistory taskHistory, Integer projectId, Integer dependencyId, String branch,
			TaskResult taskRet, boolean isDependency, boolean isRollback, String buildCommand) throws Exception {
		if (log.isInfoEnabled()) {
			log.info("Pipeline building for projectId={}", projectId);
		}
		Project project = projectDao.selectByPrimaryKey(projectId);
		Assert.notNull(project, "project not exist");

		// Obtain project source from VCS.
		String projectDir = config.getProjectDir(project.getProjectName()).getAbsolutePath();
		if (isRollback) {
			String sha;
			if (isDependency) {
				TaskSign taskSign = taskSignDao.selectByDependencyIdAndTaskId(dependencyId, taskHistory.getRefId());
				Assert.notNull(taskSign, "not found taskSign");
				sha = taskSign.getShaGit();
			} else {
				sha = taskHistory.getShaGit();
			}
			if (GitUtils.checkGitPath(projectDir)) {
				GitUtils.rollback(config.getVcs().getGitlab().getCredentials(), projectDir, sha);
				taskRet.getStringBuffer().append("project rollback success:").append(project.getProjectName()).append("\n");
			} else {
				GitUtils.clone(config.getVcs().getGitlab().getCredentials(), project.getGitUrl(), projectDir, branch);
				taskRet.getStringBuffer().append("project clone success:").append(project.getProjectName()).append("\n");
				GitUtils.rollback(config.getVcs().getGitlab().getCredentials(), projectDir, sha);
				taskRet.getStringBuffer().append("project rollback success:").append(project.getProjectName()).append("\n");
			}
		} else {
			if (GitUtils.checkGitPath(projectDir)) {// 若果目录存在则chekcout分支并pull
				GitUtils.checkout(config.getVcs().getGitlab().getCredentials(), projectDir, branch);
				taskRet.getStringBuffer().append("project checkout success:").append(project.getProjectName()).append("\n");
			} else { // 若目录不存在: 则clone 项目并 checkout 对应分支
				GitUtils.clone(config.getVcs().getGitlab().getCredentials(), project.getGitUrl(), projectDir, branch);
				taskRet.getStringBuffer().append("project clone success:").append(project.getProjectName()).append("\n");
			}
		}

		// Save the SHA of the dependency project for roll-back。
		if (isDependency) {
			TaskSign taskSign = new TaskSign();
			taskSign.setTaskId(taskHistory.getId());
			taskSign.setDependenvyId(dependencyId);
			taskSign.setShaGit(GitUtils.getLatestCommitted(projectDir));
			taskSignDao.insertSelective(taskSign);
		}

		// Building.
		if (isBlank(buildCommand)) {
			File logPath = config.getJobLog(taskHistory.getId());
			doBuildWithDefaultCommand(projectDir, logPath);
		} else {
			// Obtain temporary command file.
			File tmpCmdFile = config.getJobTmpCommandFile(taskHistory.getId(), project.getId());
			buildCommand = commandReplace(buildCommand, projectDir);
			SSHTool.execFile(buildCommand, inlog -> !ShellContextHolder.isInterruptIfNecessary(), tmpCmdFile.getAbsolutePath(),
					taskRet);
		}

	}

	/**
	 * Build with default commands.
	 * 
	 * @param projectDir
	 * @param logPath
	 * @throws Exception
	 */
	private void doBuildWithDefaultCommand(String projectDir, File logPath) throws Exception {
		String defaultCommand = "mvn -f " + projectDir + "/pom.xml clean install -Dmaven.test.skip=true | tee -a "
				+ logPath.getAbsolutePath();
		SSHTool.exec(defaultCommand, inlog -> !ShellContextHolder.isInterruptIfNecessary(), taskResult);
	}

}