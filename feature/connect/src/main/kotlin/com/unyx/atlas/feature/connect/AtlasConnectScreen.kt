package com.unyx.atlas.feature.connect

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.unyx.atlas.feature.connect.contact.ContactDetailScreen
import com.unyx.atlas.feature.connect.conversation.ConversationScreen
import com.unyx.atlas.feature.connect.pairing.AddConnectionScreen
import com.unyx.atlas.feature.connect.pairing.ConnectPairingViewModel
import com.unyx.atlas.feature.connect.profile.ProfileScreen
import kotlinx.serialization.Serializable

/** Which of `feature:connect`'s five screens is showing — see [AtlasConnectScreen]'s own doc for
 *  the 2026-10-17 decision to back this with Navigation 3 instead of local state. Every key must
 *  be `@Serializable` for [androidx.navigation3.runtime.rememberNavBackStack] to actually persist
 *  the stack across process death, not just configuration changes — the entire point of this
 *  migration. */
@Serializable
private sealed interface ConnectDestination : NavKey {
    @Serializable
    data object Connections : ConnectDestination

    @Serializable
    data object AddConnection : ConnectDestination

    @Serializable
    data class Conversation(val conversationId: String) : ConnectDestination

    @Serializable
    data class ContactDetail(val conversationId: String) : ConnectDestination

    @Serializable
    data object Profile : ConnectDestination
}

/**
 * Root of `feature:connect`.
 *
 * **Decisão de navegação (2026-10-17, revisitada deliberadamente, depois decidida com o usuário):**
 * migrado de estado local (`var destination by remember { mutableStateOf(...) }`) para Navigation 3
 * (`androidx.navigation3`) — a primeira vez essa dependência é exercitada em todo o projeto; estava
 * catalogada em `libs.versions.toml` sem nenhum uso. Motivo real, não cosmético: com estado local
 * puro, o Android matar o processo (troca de app, pouca memória) no meio de uma conversa faz reabrir
 * o app voltar pra "Conexões" em vez de retomar de onde parou — `rememberNavBackStack` resolve isso
 * de graça, desde que toda [ConnectDestination] seja `@Serializable`. Nenhum dos outros motivos que
 * normalmente justificam um `NavHost` (deep link, múltiplas pilhas independentes) se aplica aqui —
 * a notificação de nova mensagem (plano §15) continua abrindo o app de forma genérica, nunca aponta
 * pra uma conversa específica, de propósito (privacidade).
 *
 * [rememberViewModelStoreNavEntryDecorator] dá a cada entrada da pilha seu próprio `ViewModelStore`
 * — duas conversas diferentes (`Conversation("A")` e `Conversation("B")`) nunca compartilham a
 * mesma instância de `ConversationViewModel` por estarem empilhadas na mesma pilha, ao contrário do
 * que a versão com estado local fazia (tudo reutilizava o `ViewModelStoreOwner` da Activity, com
 * `ConversationViewModel.open(conversationId)` cuidando manualmente de resetar o estado — esse
 * guard continua correto e inofensivo mesmo agora que cada entrada já chega com uma instância
 * nova). `NavDisplay`'s próprio `onBack` já trata o botão/gesto de voltar do sistema automaticamente
 * — os `BackHandler` que cada tela registrava por conta própria (o fix original do bug de voltar
 * nesta sessão) foram removidos; a seta de voltar na tela continua chamando `onBack` normalmente,
 * só o back do SISTEMA que agora passa pelo `NavDisplay`.
 */
@Composable
fun AtlasConnectScreen(onBack: () -> Unit) {
    val backStack = rememberNavBackStack(ConnectDestination.Connections)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavDisplay(
            backStack = backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            onBack = {
                // At the root ("Conexões"), system back leaves the module entirely (closes
                // AtlasConnectActivity) instead of popping an empty stack.
                if (backStack.size > 1) backStack.removeLastOrNull() else onBack()
            },
            entryProvider = entryProvider {
                entry<ConnectDestination.Connections> {
                    ConnectionsScreen(
                        onBack = onBack,
                        onAddConnection = { backStack.add(ConnectDestination.AddConnection) },
                        onOpenConversation = { conversationId -> backStack.add(ConnectDestination.Conversation(conversationId)) },
                        onOpenProfile = { backStack.add(ConnectDestination.Profile) },
                    )
                }
                entry<ConnectDestination.AddConnection> {
                    val pairingViewModel: ConnectPairingViewModel = hiltViewModel()
                    AddConnectionScreen(
                        viewModel = pairingViewModel,
                        onBack = { backStack.removeLastOrNull() },
                        onDone = { backStack.removeLastOrNull() },
                    )
                }
                entry<ConnectDestination.Conversation> { key ->
                    ConversationScreen(
                        conversationId = key.conversationId,
                        onBack = { backStack.removeLastOrNull() },
                        onOpenContactDetail = { conversationId -> backStack.add(ConnectDestination.ContactDetail(conversationId)) },
                    )
                }
                entry<ConnectDestination.ContactDetail> { key ->
                    ContactDetailScreen(
                        conversationId = key.conversationId,
                        onBack = { backStack.removeLastOrNull() },
                        // The connection no longer exists once removed — back to the list, not to
                        // the conversation screen this came from (there is nothing left for it to
                        // show), so this pops BOTH entries instead of just the top one.
                        onRemoved = {
                            backStack.removeLastOrNull()
                            backStack.removeLastOrNull()
                        },
                    )
                }
                entry<ConnectDestination.Profile> {
                    ProfileScreen(onBack = { backStack.removeLastOrNull() })
                }
            },
        )
    }
}
