package com.github.zzhorizonzz.turborepo

import com.github.zzhorizonzz.turborepo.data.TurborepoPackage
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.*

class TurborepoService(private val project: Project) {
    private val LOG = Logger.getInstance(TurborepoService::class.java)
    private val runningProcesses = mutableMapOf<String, ProcessHandler>()

    fun getPackages(): List<TurborepoPackage> {
        val packages = mutableListOf<TurborepoPackage>()

        try {
            // Find turbo.json in the project
            val projectDir = project.baseDir
            val turboJson = projectDir.findChild("turbo.json")

            if (turboJson == null) {
                LOG.info("No turbo.json found in project root")
                return packages
            }

            // Check for workspace.json or package.json with workspaces
            val workspaceConfig = findWorkspaceConfig(projectDir)
            if (workspaceConfig == null) {
                LOG.info("No workspace configuration found")
                return packages
            }

            // Parse workspace configuration
            val workspacePaths = parseWorkspacePaths(workspaceConfig)

            // Find all packages based on workspace paths
            for (wsPath in workspacePaths) {
                findPackagesInWorkspace(projectDir, wsPath, packages)
            }

        } catch (e: Exception) {
            LOG.error("Error loading Turborepo packages", e)
            showNotification("Error loading Turborepo packages: ${e.message}", NotificationType.ERROR)
        }

        return packages
    }

    private fun findWorkspaceConfig(projectDir: VirtualFile): VirtualFile? {
        // Check for workspace config files in priority order
        val workspaceJson = projectDir.findChild("workspace.json")
        if (workspaceJson != null) {
            return workspaceJson
        }

        val packageJson = projectDir.findChild("package.json")
        if (packageJson != null) {
            try {
                val content = String(packageJson.contentsToByteArray(), StandardCharsets.UTF_8)
                val json = JSONObject(content)
                if (json.has("workspaces")) {
                    return packageJson
                }
            } catch (e: Exception) {
                LOG.warn("Error parsing package.json", e)
            }
        }

        return null
    }

    @Throws(JSONException::class)
    private fun parseWorkspacePaths(workspaceConfig: VirtualFile): List<String> {
        val paths = mutableListOf<String>()

        val content = String(workspaceConfig.contentsToByteArray(), StandardCharsets.UTF_8)
        val json = JSONObject(content)

        when (workspaceConfig.name) {
            "workspace.json" -> {
                // Parse workspace.json format
                if (json.has("projects")) {
                    val projects = json.getJSONObject("projects")
                    for (key in projects.keys()) {
                        paths.add(projects.getString(key))
                    }
                }
            }
            "package.json" -> {
                // Parse package.json workspaces
                if (json.has("workspaces")) {
                    val workspaces = json.get("workspaces")
                    if (workspaces is JSONArray) {
                        for (i in 0 until workspaces.length()) {
                            paths.add(workspaces.getString(i))
                        }
                    }
                }
            }
        }

        return paths
    }

    private fun findPackagesInWorkspace(projectDir: VirtualFile, workspacePath: String, packages: MutableList<TurborepoPackage>) {
        try {
            // Handle glob patterns like "packages/*"
            if (workspacePath.endsWith("/*")) {
                val dirPath = workspacePath.substring(0, workspacePath.length - 2)
                val packagesDir = projectDir.findFileByRelativePath(dirPath)

                if (packagesDir != null && packagesDir.isDirectory) {
                    for (childDir in packagesDir.children) {
                        if (childDir.isDirectory) {
                            addPackageIfValid(childDir, packages)
                        }
                    }
                }
            } else {
                // Direct path to a package
                val packageDir = projectDir.findFileByRelativePath(workspacePath)
                if (packageDir != null && packageDir.isDirectory) {
                    addPackageIfValid(packageDir, packages)
                }
            }
        } catch (e: Exception) {
            LOG.warn("Error processing workspace path: $workspacePath", e)
        }
    }

    private fun addPackageIfValid(packageDir: VirtualFile, packages: MutableList<TurborepoPackage>) {
        val packageJson = packageDir.findChild("package.json") ?: return

        try {
            val content = String(packageJson.contentsToByteArray(), StandardCharsets.UTF_8)
            val json = JSONObject(content)

            val name = json.getString("name")
            val path = packageDir.path

            // Check for dev script
            var scriptName = "dev"
            if (json.has("scripts")) {
                val scripts = json.getJSONObject("scripts")
                scriptName = when {
                    scripts.has("dev") -> "dev"
                    scripts.has("start") -> "start"
                    scripts.has("serve") -> "serve"
                    else -> "dev"
                }
            }

            packages.add(TurborepoPackage(name, path, packageDir, scriptName))

        } catch (e: Exception) {
            LOG.warn("Error parsing package.json in ${packageDir.path}", e)
        }
    }

    fun startPackage(pkg: TurborepoPackage, consoleView: ConsoleView) {
        if (pkg.isRunning) {
            return
        }

        try {
            // Create command line
            val commandLine = GeneralCommandLine()
            commandLine.workDirectory = File(pkg.path)

            // Use npm or yarn based on presence of yarn.lock
            val yarnLock = pkg.directory.findChild("yarn.lock")
            if (yarnLock != null) {
                commandLine.exePath = "yarn"
                commandLine.addParameter(pkg.scriptName)
            } else {
                commandLine.exePath = "npm"
                commandLine.addParameter("run")
                commandLine.addParameter(pkg.scriptName)
            }

            // Create process handler
            val processHandler = KillableColoredProcessHandler(commandLine)
            runningProcesses[pkg.name] = processHandler

            // Connect console to process
            consoleView.clear()
            consoleView.attachToProcess(processHandler)

            // Add process listener
            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun processTerminated(event: ProcessEvent) {
                    pkg.isRunning = false
                    runningProcesses.remove(pkg.name)
                    showNotification("Package ${pkg.name} has stopped", NotificationType.INFORMATION)
                }

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    // You can add additional processing of output if needed
                }
            })

            // Start process
            processHandler.startNotify()
            pkg.isRunning = true
            pkg.process = processHandler.process

            showNotification("Started package: ${pkg.name}", NotificationType.INFORMATION)

        } catch (e: ExecutionException) {
            LOG.error("Error starting package ${pkg.name}", e)
            showNotification("Error starting package ${pkg.name}: ${e.message}", NotificationType.ERROR)
        }
    }

    fun stopPackage(pkg: TurborepoPackage) {
        if (!pkg.isRunning) {
            return
        }

        val processHandler = runningProcesses[pkg.name]
        if (processHandler != null && !processHandler.isProcessTerminated) {
            processHandler.destroyProcess()
            pkg.isRunning = false
            runningProcesses.remove(pkg.name)
            showNotification("Stopped package: ${pkg.name}", NotificationType.INFORMATION)
        }
    }

    private fun showNotification(content: String, type: NotificationType) {
        val notification = Notification(
            "Turborepo",
            "Turborepo Plugin",
            content,
            type
        )
        Notifications.Bus.notify(notification, project)
    }
}
