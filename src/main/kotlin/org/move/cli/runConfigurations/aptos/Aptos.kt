package org.move.cli.runConfigurations.aptos

import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.execution.ParametersListUtil
import org.move.cli.Consts
import org.move.cli.MoveProject
import org.move.cli.externalLinter.ExternalLinter
import org.move.cli.externalLinter.externalLinterSettings
import org.move.cli.runConfigurations.BlockchainCli
import org.move.cli.runConfigurations.CliCommandLineArgs
import org.move.cli.settings.moveSettings
import org.move.openapiext.*
import org.move.openapiext.common.isUnitTestMode
import org.move.stdext.RsResult
import org.move.stdext.RsResult.Err
import org.move.stdext.RsResult.Ok
import org.move.stdext.unwrapOrElse
import java.nio.file.Path

data class Aptos(override val cliLocation: Path): BlockchainCli(), Disposable {
    override fun dispose() {}

    override fun init(
        project: Project,
        parentDisposable: Disposable,
        rootDirectory: VirtualFile,
        packageName: String
    ): RsProcessResult<VirtualFile> {
        if (!isUnitTestMode) {
            checkIsBackgroundThread()
        }
        val commandLine = CliCommandLineArgs(
            "move",
            listOf(
                "init",
                "--name", packageName,
                "--assume-yes"
            ),
            workingDirectory = project.rootPath
        )
        commandLine
            .toGeneralCommandLine(cliLocation)
            .execute(parentDisposable)
            .unwrapOrElse { return Err(it) }
        fullyRefreshDirectory(rootDirectory)

        val manifest =
            checkNotNull(rootDirectory.findChild(Consts.MANIFEST_FILE)) { "Can't find the manifest file" }
        return Ok(manifest)
    }

    override fun fetchPackageDependencies(
        project: Project,
        projectDir: Path,
        skipLatest: Boolean,
        owner: Disposable,
        processListener: ProcessListener
    ): RsProcessResult<Unit> {
        if (project.moveSettings.fetchAptosDeps) {
            val cli =
                CliCommandLineArgs(
                    subCommand = "move",
                    arguments = listOfNotNull(
                        "compile",
                        "--skip-fetch-latest-git-deps".takeIf { skipLatest }
                    ),
                    workingDirectory = projectDir
                )
            // TODO: as Aptos does not yet support fetching dependencies without compiling, ignore errors here,
            // TODO: still better than no call at all
            cli.toGeneralCommandLine(cliLocation)
                .execute(owner, listener = processListener)
//                .unwrapOrElse { return Err(it) }
        }
        return Ok(Unit)
    }

    fun checkProject(
        project: Project,
        owner: Disposable,
        args: AptosCompileArgs
    ): RsResult<ProcessOutput, RsProcessExecutionException.Start> {
//            val useClippy = args.linter == ExternalLinter.CLIPPY
//                    && !checkNeedInstallClippy(project, args.cargoProjectDirectory)
//            val checkCommand = if (useClippy) "clippy" else "check"
        val extraArguments = ParametersListUtil.parse(args.extraArguments)
        val commandLine =
            CliCommandLineArgs(
                "move",
                buildList {
//                        add("--message-format=json")
                    add("compile")
                    if ("--skip-fetch-latest-git-deps" !in extraArguments) {
                        add("--skip-fetch-latest-git-deps")
                    }
                    if (args.isCompilerV2 && "--compiler-version" !in extraArguments) {
                        add("--compiler-version")
                        add("v2")
                    }
                    if (args.isCompilerV2 && "--language-version" !in extraArguments) {
                        add("--language-version")
                        add("2.0")
                    }
                    addAll(ParametersListUtil.parse(args.extraArguments))
                },
                args.moveProjectDirectory,
                environmentVariables = EnvironmentVariablesData.create(args.envs, true)
            )
        return executeCommandLine(commandLine).ignoreExitCode()
    }

    fun downloadBytecode(
        moveProject: MoveProject,
        accountAddress: String,
        packageName: String
    ): RsResult<ProcessOutput, RsProcessExecutionException.Start> {
        val commandLine = CliCommandLineArgs(
            subCommand = "move download",
            arguments = listOf("--account", accountAddress, "--package", packageName, "--bytecode"),
            workingDirectory = moveProject.workingDirectory,
            environmentVariables = EnvironmentVariablesData.DEFAULT
        )
        return executeCommandLine(commandLine).ignoreExitCode()
    }

    private fun executeCommandLine(commandLine: CliCommandLineArgs): RsProcessResult<ProcessOutput> {
        return commandLine
            .toGeneralCommandLine(this.cliLocation)
            .execute(this, stdIn = null, listener = null)
    }
}

data class AptosCompileArgs(
    val linter: ExternalLinter,
    val moveProjectDirectory: Path,
    val extraArguments: String,
    val envs: Map<String, String>,
    val isCompilerV2: Boolean,
    val skipLatestGitDeps: Boolean,
) {
    companion object {
        fun forMoveProject(moveProject: MoveProject): AptosCompileArgs {
            val linterSettings = moveProject.project.externalLinterSettings
            val moveSettings = moveProject.project.moveSettings
            return AptosCompileArgs(
                linterSettings.tool,
                moveProject.workingDirectory,
//                moveProject.project.rustSettings.compileAllTargets,
                linterSettings.additionalArguments,
//                settings.channel,
                linterSettings.envs,
                isCompilerV2 = moveSettings.isCompilerV2,
                skipLatestGitDeps = moveSettings.skipFetchLatestGitDeps
            )
        }
    }
}

val MoveProject.workingDirectory: Path get() = this.currentPackage.contentRoot.pathAsPath