package org.lsposed.lspatch.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.lsposed.lspatch.Patcher
import org.lsposed.lspatch.R
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.ui.component.SelectionColumn
import org.lsposed.lspatch.ui.component.ShimmerAnimation
import org.lsposed.lspatch.ui.component.settings.SettingsCheckBox
import org.lsposed.lspatch.ui.component.settings.SettingsItem
import org.lsposed.lspatch.ui.util.*
import org.lsposed.lspatch.ui.viewmodel.NewPatchViewModel
import org.lsposed.lspatch.ui.viewmodel.NewPatchViewModel.PatchState
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo
import org.lsposed.lspatch.util.ShizukuApi
import org.lsposed.patch.util.Logger
import java.io.File

private const val TAG = "NewPatchPage"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPatchPage(from: String, entry: NavBackStackEntry) {
    val viewModel = viewModel<NewPatchViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val navController = LocalNavController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isCancelled by entry.observeState<Boolean>("isCancelled")
    LaunchedEffect(Unit) {
        lspApp.tmpApkDir.listFiles()?.forEach(File::delete)
        entry.savedStateHandle.getLiveData<AppInfo>("appInfo").observe(lifecycleOwner) {
            viewModel.configurePatch(it)
        }
    }

    Log.d(TAG, "PatchState: ${viewModel.patchState}")
    if (viewModel.patchState == PatchState.SELECTING) {
        val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { apks ->
            if (apks.isEmpty()) {
                navController.popBackStack()
                return@rememberLauncherForActivityResult
            }
            runBlocking {
                LSPPackageManager.getAppInfoFromApks(apks)
                    .onSuccess {
                        viewModel.configurePatch(it)
                    }
                    .onFailure {
                        lspApp.globalScope.launch { snackbarHost.showSnackbar(it.message ?: "Unknown error") }
                        navController.popBackStack()
                    }
            }
        }
        LaunchedEffect(Unit) {
            if (isCancelled == true) navController.popBackStack()
            else when (from) {
                "storage" -> storageLauncher.launch(arrayOf("application/vnd.android.package-archive"))
                "applist" -> navController.navigate(PageList.SelectApps.name + "?multiSelect=false")
            }
        }
    } else {
        Scaffold(
            topBar = {
                when (viewModel.patchState) {
                    PatchState.CONFIGURING -> ConfiguringTopBar { navController.popBackStack() }
                    PatchState.PATCHING,
                    PatchState.FINISHED,
                    PatchState.ERROR -> CenterAlignedTopAppBar(title = { Text(viewModel.patchApp.app.packageName) })
                    else -> Unit
                }
            },
            floatingActionButton = {
                if (viewModel.patchState == PatchState.CONFIGURING) {
                    ConfiguringFab()
                }
            }
        ) { innerPadding ->
            if (viewModel.patchState == PatchState.CONFIGURING) {
                LaunchedEffect(Unit) {
                    entry.savedStateHandle.getLiveData<SnapshotStateList<AppInfo>>("selected", SnapshotStateList()).observe(lifecycleOwner) {
                        viewModel.embeddedModules = it
                    }
                }
                PatchOptionsBody(Modifier.padding(innerPadding))
            } else {
                DoPatchBody(Modifier.padding(innerPadding))
            }
        }
    }
}

@Composable
private fun ConfiguringTopBar(onBackClick: () -> Unit) {
    SmallTopAppBar(
        title = { Text(stringResource(R.string.page_new_patch)) },
        navigationIcon = {
            IconButton(
                onClick = onBackClick,
                content = { Icon(Icons.Outlined.ArrowBack, null) }
            )
        }
    )
}

@Composable
private fun ConfiguringFab() {
    val viewModel = viewModel<NewPatchViewModel>()
    ExtendedFloatingActionButton(
        text = { Text(stringResource(R.string.patch_start)) },
        icon = { Icon(Icons.Outlined.AutoFixHigh, null) },
        onClick = { viewModel.submitPatch() }
    )
}

@Composable
private fun sigBypassLvStr(level: Int) = when (level) {
    0 -> stringResource(R.string.patch_sigbypasslv0)
    1 -> stringResource(R.string.patch_sigbypasslv1)
    2 -> stringResource(R.string.patch_sigbypasslv2)
    else -> throw IllegalArgumentException("Invalid sigBypassLv: $level")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatchOptionsBody(modifier: Modifier) {
    val viewModel = viewModel<NewPatchViewModel>()
    val navController = LocalNavController.current

    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = viewModel.patchApp.label,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Text(
            text = viewModel.patchApp.app.packageName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Text(
            text = stringResource(R.string.patch_mode),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 24.dp, bottom = 12.dp)
        )
        SelectionColumn(Modifier.padding(horizontal = 24.dp)) {
            SelectionItem(
                selected = viewModel.useManager,
                onClick = { viewModel.useManager = true },
                icon = Icons.Outlined.Api,
                title = stringResource(R.string.patch_local),
                desc = stringResource(R.string.patch_local_desc)
            )
            SelectionItem(
                selected = !viewModel.useManager,
                onClick = { viewModel.useManager = false },
                icon = Icons.Outlined.WorkOutline,
                title = stringResource(R.string.patch_portable),
                desc = stringResource(R.string.patch_portable_desc),
                extraContent = {
                    TextButton(
                        onClick = { navController.navigate(PageList.SelectApps.name + "?multiSelect=true") },
                        content = { Text(text = stringResource(R.string.patch_embed_modules), style = MaterialTheme.typography.bodyLarge) }
                    )
                }
            )
        }
        SettingsCheckBox(
            modifier = Modifier.padding(top = 6.dp),
            checked = viewModel.debuggable,
            onClick = { viewModel.debuggable = !viewModel.debuggable },
            icon = Icons.Outlined.BugReport,
            title = stringResource(R.string.patch_debuggable)
        )
        SettingsCheckBox(
            checked = viewModel.overrideVersionCode,
            onClick = { viewModel.overrideVersionCode = !viewModel.overrideVersionCode },
            icon = Icons.Outlined.Layers,
            title = stringResource(R.string.patch_override_version_code),
            desc = stringResource(R.string.patch_override_version_code_desc)
        )
        Box {
            var expanded by remember { mutableStateOf(false) }
            SettingsItem(
                onClick = { expanded = true },
                icon = Icons.Outlined.Edit,
                title = stringResource(R.string.patch_sign),
                desc = viewModel.sign.mapIndexedNotNull { index, on -> if (on) "V" + (index + 1) else null }.joinToString(" + ").ifEmpty { "None" }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                repeat(2) { index ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = viewModel.sign[index], onCheckedChange = { viewModel.sign[index] = !viewModel.sign[index] })
                                Text("V" + (index + 1))
                            }
                        },
                        onClick = { viewModel.sign[index] = !viewModel.sign[index] }
                    )
                }
            }
        }
        Box {
            var expanded by remember { mutableStateOf(false) }
            SettingsItem(
                onClick = { expanded = true },
                icon = Icons.Outlined.RemoveModerator,
                title = stringResource(R.string.patch_sigbypass),
                desc = sigBypassLvStr(viewModel.sigBypassLevel)
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                repeat(3) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = viewModel.sigBypassLevel == it, onClick = { viewModel.sigBypassLevel = it })
                                Text(sigBypassLvStr(it))
                            }
                        },
                        onClick = {
                            viewModel.sigBypassLevel = it
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private class PatchLogger(private val logs: MutableList<Pair<Int, String>>) : Logger() {
    override fun d(msg: String) {
        if (verbose) {
            Log.d(TAG, msg)
            logs += Log.DEBUG to msg
        }
    }

    override fun i(msg: String) {
        Log.i(TAG, msg)
        logs += Log.INFO to msg
    }

    override fun e(msg: String) {
        Log.e(TAG, msg)
        logs += Log.ERROR to msg
    }
}

@Composable
private fun DoPatchBody(modifier: Modifier) {
    val viewModel = viewModel<NewPatchViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<Pair<Int, String>>() }
    val logger = remember { PatchLogger(logs) }

    LaunchedEffect(Unit) {
        try {
            Patcher.patch(logger, viewModel.patchOptions)
            viewModel.finishPatch()
        } catch (t: Throwable) {
            logger.e(t.message.orEmpty())
            logger.e(t.stackTraceToString())
            viewModel.failPatch()
        } finally {
            lspApp.tmpApkDir.listFiles()?.forEach(File::delete)
        }
    }

    BoxWithConstraints(modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        val shellBoxMaxHeight =
            if (viewModel.patchState == PatchState.PATCHING) maxHeight
            else maxHeight - ButtonDefaults.MinHeight - 12.dp
        Column(
            Modifier
                .fillMaxSize()
                .wrapContentHeight()
                .animateContentSize(spring(stiffness = Spring.StiffnessLow))
        ) {
            ShimmerAnimation(enabled = viewModel.patchState == PatchState.PATCHING) {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                ) {
                    val scrollState = rememberLazyListState()
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = shellBoxMaxHeight)
                            .clip(RoundedCornerShape(32.dp))
                            .background(brush)
                            .padding(horizontal = 24.dp, vertical = 18.dp)
                    ) {
                        items(logs) {
                            when (it.first) {
                                Log.DEBUG -> Text(text = it.second)
                                Log.INFO -> Text(text = it.second)
                                Log.ERROR -> Text(text = it.second, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    LaunchedEffect(scrollState.lastItemIndex) {
                        if (!scrollState.isScrolledToEnd) {
                            scrollState.animateScrollToItem(scrollState.lastItemIndex!!)
                        }
                    }
                }
            }

            when (viewModel.patchState) {
                PatchState.PATCHING -> BackHandler {}
                PatchState.FINISHED -> {
                    val shizukuUnavailable = stringResource(R.string.shizuku_unavailable)
                    val installSuccessfully = stringResource(R.string.patch_install_successfully)
                    val installFailed = stringResource(R.string.patch_install_failed)
                    val copyError = stringResource(R.string.patch_copy_error)
                    var installing by rememberSaveable { mutableStateOf(false) }
                    if (installing) InstallDialog(viewModel.patchApp) { status, message ->
                        scope.launch {
                            LSPPackageManager.fetchAppList()
                            installing = false
                            if (status == PackageInstaller.STATUS_SUCCESS) {
                                lspApp.globalScope.launch { snackbarHost.showSnackbar(installSuccessfully) }
                                navController.popBackStack()
                            } else if (status != LSPPackageManager.STATUS_USER_CANCELLED) {
                                val result = snackbarHost.showSnackbar(installFailed, copyError)
                                if (result == SnackbarResult.ActionPerformed) {
                                    val cm = lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("LSPatch", message))
                                }
                            }
                        }
                    }
                    Row(Modifier.padding(top = 12.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { navController.popBackStack() },
                            content = { Text(stringResource(R.string.patch_return)) }
                        )
                        Spacer(Modifier.weight(0.2f))
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (!ShizukuApi.isPermissionGranted) {
                                    scope.launch {
                                        snackbarHost.showSnackbar(shizukuUnavailable)
                                    }
                                } else {
                                    installing = true
                                }
                            },
                            content = { Text(stringResource(R.string.patch_install)) }
                        )
                    }
                }
                PatchState.ERROR -> {
                    Row(Modifier.padding(top = 12.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { navController.popBackStack() },
                            content = { Text(stringResource(R.string.patch_return)) }
                        )
                        Spacer(Modifier.weight(0.2f))
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val cm = lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("LSPatch", logs.joinToString { it.second + "\n" }))
                            },
                            content = { Text(stringResource(R.string.patch_copy_error)) }
                        )
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun InstallDialog(patchApp: AppInfo, onFinish: (Int, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var uninstallFirst by remember { mutableStateOf(ShizukuApi.isPackageInstalledWithoutPatch(patchApp.app.packageName)) }
    var installing by remember { mutableStateOf(0) }
    val doInstall = suspend {
        Log.i(TAG, "Installing app ${patchApp.app.packageName}")
        installing = 1
        val (status, message) = LSPPackageManager.install()
        installing = 0
        Log.i(TAG, "Installation end: $status, $message")
        onFinish(status, message)
    }

    LaunchedEffect(Unit) {
        if (!uninstallFirst) {
            doInstall()
        }
    }

    if (uninstallFirst) {
        AlertDialog(
            onDismissRequest = { onFinish(LSPPackageManager.STATUS_USER_CANCELLED, "User cancelled") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            Log.i(TAG, "Uninstalling app ${patchApp.app.packageName}")
                            uninstallFirst = false
                            installing = 2
                            val (status, message) = LSPPackageManager.uninstall(patchApp.app.packageName)
                            installing = 0
                            Log.i(TAG, "Uninstallation end: $status, $message")
                            if (status == PackageInstaller.STATUS_SUCCESS) {
                                doInstall()
                            } else {
                                onFinish(status, message)
                            }
                        }
                    },
                    content = { Text(stringResource(android.R.string.ok)) }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { onFinish(LSPPackageManager.STATUS_USER_CANCELLED, "User cancelled") },
                    content = { Text(stringResource(android.R.string.cancel)) }
                )
            },
            title = {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.patch_uninstall),
                    textAlign = TextAlign.Center
                )
            },
            text = { Text(stringResource(R.string.patch_uninstall_text)) }
        )
    }

    if (installing != 0) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(if (installing == 1) R.string.patch_installing else R.string.patch_uninstalling),
                    fontFamily = FontFamily.Serif,
                    textAlign = TextAlign.Center
                )
            }
        )
    }
}
