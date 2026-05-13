package live.agor.jetbrains.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "AgorSettings", storages = [Storage("agor.xml")])
class AgorSettings : PersistentStateComponent<AgorSettings.StateData> {
    data class StateData(
        var agorUrl: String = "http://localhost:3030",
        var splitLayoutMode: String = "auto",
    )

    private var state = StateData()

    override fun getState(): StateData = state

    override fun loadState(state: StateData) {
        this.state = state
    }

    var agorToken: String?
        get() = PasswordSafe.instance.getPassword(credentialAttributes("agor-token"))
        set(value) = PasswordSafe.instance.set(credentialAttributes("agor-token"), value?.let { Credentials("", it) })

    companion object {
        fun getInstance(): AgorSettings =
            ApplicationManager.getApplication().getService(AgorSettings::class.java)

        private fun credentialAttributes(key: String): CredentialAttributes =
            CredentialAttributes("live.agor.jetbrains.$key")
    }
}
