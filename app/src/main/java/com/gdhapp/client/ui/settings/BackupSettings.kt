package com.gdhapp.client.ui.settings

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.gdhapp.client.GdhappApplication
import com.gdhapp.client.R
import com.gdhapp.client.data.backup.*
import com.gdhapp.client.i18n.AppText
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.time.LocalDate

enum class BackupPasswordMode { CREATE, OPEN }

/** Survives the system document picker and rotation. Passwords are never saved to SavedState or disk. */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<GdhappApplication>()
    var busy by mutableStateOf(false); private set
    var passwordMode by mutableStateOf<BackupPasswordMode?>(null)
    var preview by mutableStateOf<BackupSnapshot?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    private var exportBytes by mutableStateOf<ByteArray?>(null)
    private var importBytes: ByteArray? = null
    val awaitingExport get() = exportBytes != null

    fun cancel() { if (!busy) { passwordMode = null; preview = null; importBytes?.fill(0); importBytes = null; message = null } }
    private fun problem(error: Exception): String = AppText.text(when ((error as? BackupException)?.problem) {
        BackupProblem.PASSWORD -> R.string.backup_wrong_password
        BackupProblem.DAMAGED -> R.string.backup_invalid
        BackupProblem.UNSUPPORTED -> R.string.backup_newer_version
        BackupProblem.TOO_LARGE -> R.string.backup_too_large
        BackupProblem.VPN_ACTIVE -> R.string.backup_disconnect
        else -> R.string.backup_file_error
    })
    private fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = problem(error) }
            finally { busy = false }
        }
    }
    fun create(password: CharArray?, chooseFile: () -> Unit) = task {
        exportBytes?.fill(0)
        exportBytes = app.backups.create(password)
        passwordMode = null
        try { chooseFile() } catch (error: Exception) { exportBytes?.fill(0); exportBytes = null; throw error }
    }
    fun write(uri: Uri?) {
        val bytes = exportBytes ?: return
        exportBytes = null
        if (uri == null) { bytes.fill(0); return }
        task {
            try { withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("No output")
            }; message = AppText.text(R.string.backup_saved) }
            finally { bytes.fill(0) }
        }
    }
    fun open(uri: Uri?) {
        if (uri == null) return
        task {
            importBytes?.fill(0)
            importBytes = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count == -1) break
                        if (out.size() + count > BackupCipher.MAX_BYTES) throw BackupException(BackupProblem.TOO_LARGE)
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                } ?: error("No input")
            }
            val bytes = checkNotNull(importBytes)
            if (BackupCipher.isEncrypted(bytes)) passwordMode = BackupPasswordMode.OPEN else {
                try { preview = app.backups.read(bytes) }
                finally { bytes.fill(0); importBytes = null }
            }
        }
    }
    fun unlock(password: CharArray) = task {
        val bytes = importBytes ?: throw BackupException(BackupProblem.DAMAGED)
        preview = app.backups.read(bytes, password)
        bytes.fill(0); importBytes = null; passwordMode = null
    }
    fun restore() = task {
        val snapshot = preview ?: return@task
        app.backups.restore(snapshot)
        preview = null; message = AppText.text(R.string.backup_restored)
    }
    override fun onCleared() { exportBytes?.fill(0); importBytes?.fill(0) }
}

@Composable
internal fun BackupSettings() {
    val context = LocalContext.current
    val owner = checkNotNull(LocalViewModelStoreOwner.current)
    val vm = remember(owner) { ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[BackupViewModel::class.java] }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), vm::write)
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::open)
    SettingsSection(AppText.text(R.string.backup_title), Icons.Rounded.Backup) {
        SettingsHint(AppText.text(R.string.backup_hint))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.cancel(); vm.passwordMode = BackupPasswordMode.CREATE }, enabled = !vm.busy && !vm.awaitingExport,
                modifier = Modifier.weight(1f)) { Text(AppText.text(R.string.backup_create)) }
            OutlinedButton(onClick = { vm.cancel(); openFile.launch(arrayOf("application/octet-stream", "application/*", "*/*")) },
                enabled = !vm.busy && !vm.awaitingExport, modifier = Modifier.weight(1f)) { Text(AppText.text(R.string.backup_open)) }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.passwordMode == null && vm.preview == null) vm.message?.let { SettingsHint(it) }
    }
    vm.passwordMode?.let { mode ->
        var password by remember(mode) { mutableStateOf("") }
        var confirm by remember(mode) { mutableStateOf("") }
        var passwordProtected by androidx.compose.runtime.saveable.rememberSaveable(mode) { mutableStateOf(true) }
        val creating = mode == BackupPasswordMode.CREATE
        AlertDialog(onDismissRequest = vm::cancel, properties = DialogProperties(dismissOnBackPress = !vm.busy, dismissOnClickOutside = !vm.busy),
            title = { Text(AppText.text(if (creating) R.string.backup_create else R.string.backup_open)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (creating) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(passwordProtected, onClick = { passwordProtected = true }, label = { Text(AppText.text(R.string.backup_with_password)) }, enabled = !vm.busy)
                    FilterChip(!passwordProtected, onClick = { passwordProtected = false; password = ""; confirm = "" }, label = { Text(AppText.text(R.string.backup_without_password)) }, enabled = !vm.busy)
                }
                Text(AppText.text(if (!creating) R.string.backup_enter_password else if (passwordProtected) R.string.backup_password_hint else R.string.backup_unprotected_hint))
                if (!creating || passwordProtected) {
                    OutlinedTextField(password, { if (it.length <= 1024) password = it }, label = { Text(AppText.text(R.string.backup_password)) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !vm.busy)
                }
                if (creating && passwordProtected) OutlinedTextField(confirm, { if (it.length <= 1024) confirm = it }, label = { Text(AppText.text(R.string.backup_confirm_password)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !vm.busy)
                vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } }, confirmButton = { TextButton(enabled = !vm.busy && if (creating) !passwordProtected || password.length >= 8 && password == confirm else password.isNotEmpty(), onClick = {
                val chars = if (creating && !passwordProtected) null else password.toCharArray(); password = ""; confirm = ""
                if (creating) vm.create(chars) { createFile.launch("NyaWire-${LocalDate.now()}.nyawire") } else vm.unlock(checkNotNull(chars))
            }) { Text(AppText.text(if (creating) R.string.backup_save else R.string.backup_unlock)) } },
            dismissButton = { TextButton(onClick = vm::cancel, enabled = !vm.busy) { Text(AppText.text(R.string.cancel)) } })
    }
    vm.preview?.let { snapshot ->
        AlertDialog(onDismissRequest = vm::cancel, properties = DialogProperties(dismissOnBackPress = !vm.busy, dismissOnClickOutside = !vm.busy),
            title = { Text(AppText.text(R.string.backup_restore_title)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AppText.text(R.string.backup_preview, snapshot.groups.size, snapshot.groups.sumOf { it.servers.size }))
                Text(AppText.text(R.string.backup_replace_hint))
                vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } }, confirmButton = { TextButton(onClick = vm::restore, enabled = !vm.busy) { Text(AppText.text(R.string.backup_restore)) } },
            dismissButton = { TextButton(onClick = vm::cancel, enabled = !vm.busy) { Text(AppText.text(R.string.cancel)) } })
    }
}
