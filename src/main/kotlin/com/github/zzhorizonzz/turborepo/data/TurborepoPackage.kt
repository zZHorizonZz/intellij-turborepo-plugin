package com.github.zzhorizonzz.turborepo.data

import com.intellij.openapi.vfs.VirtualFile

data class TurborepoPackage(
    val name: String,
    val path: String,
    val directory: VirtualFile,
    val scriptName: String
) {
    var isRunning: Boolean = false
    var process: Process? = null
}