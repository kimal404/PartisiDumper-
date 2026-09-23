package id.dumper.parti.data.repository

import com.topjohnwu.superuser.Shell
import id.dumper.parti.data.model.RootState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootRepository {
    suspend fun checkRootAccess(): RootState = withContext(Dispatchers.IO) {
        try {
            if (Shell.isAppGrantedRoot() == true) {
                return@withContext RootState.Granted
            }
            val shell = Shell.getShell()
            if (shell.isRoot) {
                RootState.Granted
            } else {
                RootState.Denied("Akses root ditolak oleh superuser manager.")
            }
        } catch (e: Exception) {
            RootState.Denied("Gagal inisialisasi root shell: ${e.localizedMessage ?: "Unknown error"}")
        }
    }
}
