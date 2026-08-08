@file:Suppress("RedundantNullableReturnType", "unused")

package suwayomi.tachidesk.graphql.mutations

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.server.JavalinSetup.future
import java.io.File
import java.util.concurrent.CompletableFuture

class SystemMutation {
    data class GlobalSyncInput(
        val clientMutationId: String? = null,
    )

    data class GlobalSyncPayload(
        val clientMutationId: String?,
        val success: Boolean,
        val message: String,
    )

    @RequireAuth
    fun triggerGlobalSync(input: GlobalSyncInput): CompletableFuture<GlobalSyncPayload?> {
        return future {
            try {
                val pid = ProcessHandle.current().pid()
                val scriptPath = "C:\\Users\\kevin\\Downloads\\global-sync.ps1"
                val logDir = "C:\\Users\\kevin\\Downloads"
                val outputLog = "$logDir\\sync-output.log"
                val errorLog = "$logDir\\sync-error.log"

                val process =
                    ProcessBuilder(
                        "powershell.exe",
                        "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", scriptPath,
                        "-ServerPid", pid.toString(),
                    )
                        .redirectOutput(ProcessBuilder.Redirect.to(File(outputLog)))
                        .redirectError(ProcessBuilder.Redirect.to(File(errorLog)))
                        .start()

                GlobalSyncPayload(
                    clientMutationId = input.clientMutationId,
                    success = true,
                    message = "Global sync started (Server PID: $pid).",
                )
            } catch (e: Exception) {
                GlobalSyncPayload(
                    clientMutationId = input.clientMutationId,
                    success = false,
                    message = "Failed to start sync: ${e.message}",
                )
            }
        }
    }
}
