package live.agor.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.agor.app.AppContainer
import live.agor.app.update.UpdateInfo

/**
 * Coordinates the in-app update flow: check → download → install.
 *
 * State machine:
 *   Idle → Checking → (UpToDate | Available)
 *   Available → Downloading → (DownloadFailed | Ready)
 *   Ready → install (system dialog)
 */
class UpdateViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val info: UpdateInfo, val downloaded: Long, val total: Long) : State
        data class Ready(val info: UpdateInfo, val apkPath: String) : State
        data class Failed(val info: UpdateInfo?, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Silent background check used at app start. Never surfaces errors. */
    fun checkSilently() {
        if (_state.value !is State.Idle && _state.value !is State.UpToDate) return
        viewModelScope.launch {
            val info = container.updateChecker.check() ?: run {
                _state.value = State.UpToDate
                return@launch
            }
            _state.value = State.Available(info)
        }
    }

    /** Manual check (Settings → "Check for updates"). Surfaces "you're up to date". */
    fun checkExplicit() {
        viewModelScope.launch {
            _state.value = State.Checking
            val info = container.updateChecker.check()
            _state.value = if (info == null) State.UpToDate else State.Available(info)
        }
    }

    fun download() {
        val available = _state.value as? State.Available ?: return
        viewModelScope.launch {
            _state.update { State.Downloading(available.info, 0, available.info.sizeBytes) }
            val file = container.updateInstaller.download(available.info) { downloaded, total ->
                _state.update {
                    when (it) {
                        is State.Downloading -> it.copy(downloaded = downloaded, total = total)
                        else -> it
                    }
                }
            }
            _state.value = if (file != null) {
                State.Ready(available.info, file.absolutePath)
            } else {
                State.Failed(available.info, "Download failed")
            }
        }
    }

    /**
     * Hand the downloaded APK to the system installer. If the user hasn't yet
     * granted REQUEST_INSTALL_PACKAGES, returns false — the caller should then
     * call [requestInstallPermission] to send them to the toggle screen.
     */
    fun install(): Boolean {
        val ready = _state.value as? State.Ready ?: return false
        val ok = container.updateInstaller.install(java.io.File(ready.apkPath))
        if (!ok) _state.value = State.Failed(ready.info, "Install permission required")
        return ok
    }

    fun canRequestInstall(): Boolean = container.updateInstaller.canRequestInstall()

    fun requestInstallPermission() {
        container.updateInstaller.requestInstallPermission()
    }

    fun dismiss() {
        _state.value = State.Idle
    }
}
