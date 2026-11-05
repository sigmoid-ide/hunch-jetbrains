package com.github.sigmoidide.hunch.ui

import com.github.sigmoidide.hunch.model.HunchStore
import com.github.sigmoidide.hunch.model.Milestone
import com.github.sigmoidide.hunch.model.Run
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.Paths

/** What the tool window shows: the project's runs, reloaded whenever .hunch changes. */
data class HunchState(
    val store: HunchStore,
    val runs: List<Run>,
    val milestones: List<Milestone>,
    val mainMetric: String?,
    val loaded: Boolean,
)

/**
 * Watches the project's .hunch folder from a background coroutine and publishes its runs.
 * Runs are written by other processes (hunch run, training scripts), so this polls a cheap
 * signature of the folder instead of relying on VFS events, which only cover loaded files.
 */
@Service(Service.Level.PROJECT)
class HunchService(private val project: Project, val scope: CoroutineScope) {
    private val root: Path = project.guessProjectDir()?.toNioPathOrNull() ?: Paths.get(project.basePath ?: ".")
    private val _state = MutableStateFlow(HunchState(HunchStore.find(root), emptyList(), emptyList(), null, loaded = false))
    val state: StateFlow<HunchState> = _state
    private var signature = Long.MIN_VALUE

    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                reloadIfChanged()
                delay(POLL_MILLIS)
            }
        }
    }

    /** Reloads now, for example after the plugin wrote a note. */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            signature = Long.MIN_VALUE
            reloadIfChanged()
        }
    }

    private fun reloadIfChanged() {
        // The store may appear after the first run, possibly in a parent folder.
        val store = _state.value.store.takeIf { it.exists() } ?: HunchStore.find(root)
        val current = store.signature()
        if (current == signature && _state.value.loaded) return
        signature = current
        _state.value = HunchState(store, store.runs(), store.milestones(), store.mainMetric(), loaded = true)
    }

    private fun com.intellij.openapi.vfs.VirtualFile.toNioPathOrNull(): Path? =
        try { toNioPath() } catch (_: UnsupportedOperationException) { null }

    companion object {
        private const val POLL_MILLIS = 2000L

        fun getInstance(project: Project): HunchService = project.service()
    }
}
