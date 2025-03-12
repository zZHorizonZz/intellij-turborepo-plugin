package com.github.zzhorizonzz.turborepo.actions

import com.github.zzhorizonzz.turborepo.TurborepoIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class RefreshPackagesAction : AnAction(
    "Refresh Turborepo Packages",
    "Refresh Turborepo packages in the current project",
    TurborepoIcons.REFRESH
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project != null) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Turborepo")
            toolWindow?.show()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}