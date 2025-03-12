package com.github.zzhorizonzz.turborepo

import com.github.zzhorizonzz.turborepo.data.TurborepoPackage
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import javax.swing.*

class TurborepoToolWindowContent(
    private val project: Project,
    private val toolWindow: ToolWindow
) {
    private val mainPanel = JPanel(BorderLayout())
    private lateinit var packageList: JBList<TurborepoPackage>
    private val consoleViews = mutableMapOf<String, ConsoleView>()
    private val packagePanels = mutableMapOf<String, JPanel>()
    private val detailPanel: JPanel
    private val cardLayout: CardLayout
    private val turborepoService = project.getService(TurborepoService::class.java)

    init {
        // Main split panel
        val splitter = JBSplitter(false)
        splitter.firstComponent = createPackageListPanel()

        // Detail panel with card layout to show different package consoles
        cardLayout = CardLayout()
        detailPanel = JPanel(cardLayout)
        splitter.secondComponent = detailPanel
        splitter.proportion = 0.3f

        mainPanel.add(splitter, BorderLayout.CENTER)

        // Initialize packages
        loadPackages()
    }

    private fun createPackageListPanel(): JPanel {
        val listModel = DefaultListModel<TurborepoPackage>()
        packageList = JBList(listModel)
        packageList.cellRenderer = TurborepoPackageRenderer()
        packageList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedPackage = packageList.selectedValue
                selectedPackage?.let {
                    cardLayout.show(detailPanel, it.name)
                }
            }
        }

        val packagePanel = JPanel(BorderLayout())
        packagePanel.add(JBScrollPane(packageList), BorderLayout.CENTER)

        // Add toolbar with actions
        val actionGroup = DefaultActionGroup()
        actionGroup.add(RefreshPackagesAction())
        actionGroup.add(StartAllPackagesAction())
        actionGroup.add(StopAllPackagesAction())

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "TurborepoPackagesList", actionGroup, true)
        packagePanel.add(toolbar.component, BorderLayout.NORTH)

        return packagePanel
    }

    private fun loadPackages() {
        // Clear existing models
        val model = packageList.model as DefaultListModel<TurborepoPackage>
        model.clear()

        // Get packages from service
        val packages = turborepoService.getPackages()

        // Add packages to list and create console panels
        for (pkg in packages) {
            model.addElement(pkg)

            // Create package panel with console and actions if it doesn't exist
            if (!packagePanels.containsKey(pkg.name)) {
                val packagePanel = createPackagePanel(pkg)
                packagePanels[pkg.name] = packagePanel
                detailPanel.add(packagePanel, pkg.name)
            }
        }

        // Select first package if available
        if (packages.isNotEmpty()) {
            packageList.selectedIndex = 0
        }
    }

    private fun createPackagePanel(pkg: TurborepoPackage): JPanel {
        val panel = JPanel(BorderLayout())

        // Create console view
        val consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        consoleViews[pkg.name] = consoleView

        // Create toolbar with package-specific actions
        val actionGroup = DefaultActionGroup()
        actionGroup.add(StartPackageAction(pkg))
        actionGroup.add(StopPackageAction(pkg))
        actionGroup.add(RestartPackageAction(pkg))

        // Add console actions
        actionGroup.addSeparator()
        /*for (action in consoleView.createConsoleActions()) {
            actionGroup.add(action)
        }*/

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "TurborepoPackageConsole", actionGroup, false)

        // Add components to panel
        panel.add(toolbar.component, BorderLayout.NORTH)
        panel.add(consoleView.component, BorderLayout.CENTER)

        // Add status panel
        val statusPanel = JPanel(BorderLayout())
        statusPanel.border = JBUI.Borders.empty(2, 5)
        statusPanel.add(JLabel(pkg.name), BorderLayout.WEST)

        val statusLabel = JLabel("Stopped")
        statusLabel.foreground = Color.GRAY
        statusPanel.add(statusLabel, BorderLayout.EAST)

        panel.add(statusPanel, BorderLayout.SOUTH)

        return panel
    }

    val content: JComponent
        get() = mainPanel

    // Custom renderer for turborepo packages
    private inner class TurborepoPackageRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel

            if (value is TurborepoPackage) {
                label.text = value.name

                // Set different icon based on running state
                if (value.isRunning) {
                    label.icon = TurborepoIcons.PACKAGE_RUNNING
                } else {
                    label.icon = TurborepoIcons.PACKAGE
                }
            }

            return label
        }
    }

    // Actions
    private inner class RefreshPackagesAction : AnAction(
        "Refresh Packages",
        "Refresh Turborepo packages",
        TurborepoIcons.REFRESH
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            loadPackages()
        }
    }

    private inner class StartPackageAction(private val pkg: TurborepoPackage) : AnAction(
        "Start",
        "Start package",
        TurborepoIcons.START
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            turborepoService.startPackage(pkg, consoleViews[pkg.name]!!)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !pkg.isRunning
        }
    }

    private inner class StopPackageAction(private val pkg: TurborepoPackage) : AnAction(
        "Stop",
        "Stop package",
        TurborepoIcons.STOP
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            turborepoService.stopPackage(pkg)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = pkg.isRunning
        }
    }

    private inner class RestartPackageAction(private val pkg: TurborepoPackage) : AnAction(
        "Restart",
        "Restart package",
        TurborepoIcons.RESTART
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val consoleView = consoleViews[pkg.name]!!
            turborepoService.stopPackage(pkg)
            turborepoService.startPackage(pkg, consoleView)
        }
    }

    private inner class StartAllPackagesAction : AnAction(
        "Start All",
        "Start all packages",
        TurborepoIcons.START_ALL
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val model = packageList.model as DefaultListModel<TurborepoPackage>
            for (i in 0 until model.size()) {
                val pkg = model.getElementAt(i)
                if (!pkg.isRunning) {
                    turborepoService.startPackage(pkg, consoleViews[pkg.name]!!)
                }
            }
        }
    }

    private inner class StopAllPackagesAction : AnAction(
        "Stop All",
        "Stop all packages",
        TurborepoIcons.STOP_ALL
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val model = packageList.model as DefaultListModel<TurborepoPackage>
            for (i in 0 until model.size()) {
                val pkg = model.getElementAt(i)
                if (pkg.isRunning) {
                    turborepoService.stopPackage(pkg)
                }
            }
        }
    }
}