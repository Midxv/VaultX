package vault.x.org

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.floor

enum class Screen { HOME, RECYCLE_BIN, MAP_VIEW }
enum class ViewMode { LIST, GRID, TINY }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(userPin: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // MANAGERS
    val crypto = remember { CryptoManager(context) }
    val vaultManager = remember { VaultManager(context) }
    val fileManager = remember { FileManager(context, crypto) }
    val thumbManager = remember { ThumbnailManager(context, crypto) }
    val metaManager = remember { MetadataManager(context, crypto) }
    val workManager = remember { WorkManager.getInstance(context) }

    // STATE
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var currentFolderId by remember { mutableStateOf<String?>(null) }

    // VIEW OPTIONS
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }
    var showDates by remember { mutableStateOf(true) }
    var showViewMenu by remember { mutableStateOf(false) }

    var allItems by remember { mutableStateOf(listOf<VaultItem>()) }
    var selectedItems by remember { mutableStateOf(setOf<String>()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    var thumbnailPaths by remember { mutableStateOf(mapOf<String, String>()) }
    var mapMarkers by remember { mutableStateOf<List<MediaMeta>>(emptyList()) }

    var isFabExpanded by remember { mutableStateOf(false) }
    var viewerStartIndex by remember { mutableStateOf<Int?>(null) }

    // PROGRESS
    var isSyncingThumbs by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableFloatStateOf(0f) }
    var isImporting by remember { mutableStateOf(false) }

    // SETTINGS STATE
    var biometricEnabled by remember { mutableStateOf(vaultManager.isBiometricEnabled()) }
    var screenshotBlocked by remember { mutableStateOf(vaultManager.isScreenshotBlockEnabled()) }

    // APPLY SCREENSHOT BLOCKER REAL-TIME
    LaunchedEffect(screenshotBlocked) {
        if (screenshotBlocked) {
            activity?.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // DIALOGS
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEmptyBinDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState()

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val items = fileManager.loadFiles(currentFolderId)

            withContext(Dispatchers.Main) {
                allItems = items
                thumbnailPaths = thumbManager.getThumbnailMap()
            }

            withContext(Dispatchers.Main) { isSyncingThumbs = true }
            thumbManager.ensureThumbnails(userPin, items) { progress ->
                syncProgress = progress
                if (progress >= 1.0f) {
                    val thumbs = thumbManager.getThumbnailMap()
                    scope.launch(Dispatchers.Main) {
                        thumbnailPaths = thumbs
                        isSyncingThumbs = false
                    }
                }
            }
            val markers = metaManager.getMapMarkers()
            withContext(Dispatchers.Main) { mapMarkers = markers }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    BackHandler {
        when {
            showContextMenu -> { showContextMenu = false; isSelectionMode = false; selectedItems = emptySet() }
            isSelectionMode -> { isSelectionMode = false; selectedItems = emptySet() }
            isFabExpanded -> isFabExpanded = false
            currentScreen != Screen.HOME -> currentScreen = Screen.HOME
            currentFolderId != null -> { currentFolderId = null; refresh() }
            else -> (context as? Activity)?.finish()
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            if (isImporting) { Toast.makeText(context, "Importing...", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
            isImporting = true; isFabExpanded = false

            scope.launch(Dispatchers.IO) {
                try {
                    uris.forEach { try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_:Exception){} }
                    val cacheFile = File(context.cacheDir, "import_queue.txt")
                    cacheFile.writeText(uris.joinToString("\n") { it.toString() })
                    val request = OneTimeWorkRequestBuilder<FileWorker>().setInputData(workDataOf("ACTION" to "IMPORT", "PIN" to userPin, "URI_LIST_FILE" to cacheFile.absolutePath, "PARENT_ID" to currentFolderId)).build()
                    workManager.enqueue(request)
                    withContext(Dispatchers.Main) {
                        launch {
                            workManager.getWorkInfoByIdFlow(request.id).collect {
                                if (it?.state?.isFinished == true) {
                                    isImporting = false; refresh(); Toast.makeText(context, "Done", Toast.LENGTH_SHORT).show()
                                    this.cancel()
                                }
                            }
                        }
                    }
                } catch(e: Exception) { withContext(Dispatchers.Main) { isImporting = false } }
            }
        }
    }

    fun shareSelectedItems() {
        scope.launch(Dispatchers.IO) {
            val files = allItems.filter { selectedItems.contains(it.id) && it.type != ItemType.FOLDER }
            val uris = ArrayList<Uri>()
            val cacheDir = File(context.cacheDir, "shared").apply { mkdirs() }
            files.forEach { item ->
                val dest = File(cacheDir, item.name)
                if (crypto.decryptToStream(userPin, File(item.path), FileOutputStream(dest))) uris.add(FileProvider.getUriForFile(context, "${context.packageName}.provider", dest))
            }
            if (uris.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                withContext(Dispatchers.Main) { context.startActivity(Intent.createChooser(intent, "Share")); showContextMenu = false; isSelectionMode = false; selectedItems = emptySet() }
            }
        }
    }

    val displayItems = remember(allItems, searchQuery, currentScreen, currentFolderId) {
        var list = if (currentScreen == Screen.RECYCLE_BIN) allItems.filter { it.isDeleted } else allItems.filter { !it.isDeleted }
        if (searchQuery.isNotEmpty()) list = list.filter { it.name.contains(searchQuery, true) }
        list
    }

    val groupedItems = remember(displayItems, showDates) {
        if (!showDates) mapOf("All" to displayItems)
        else displayItems.groupBy {
            if(it.type == ItemType.FOLDER) "Albums"
            else {
                val date = Date(it.dateModified)
                val today = Date()
                val sdf = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
                if (sdf.format(date) == sdf.format(today)) "Today" else sdf.format(date)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(32.dp))
                Image(painter = painterResource(id = R.drawable.vaultx), contentDescription = null, modifier = Modifier.padding(16.dp).size(64.dp))
                Text("VaultX", modifier = Modifier.padding(start = 16.dp, bottom = 16.dp), color = VioletAccent, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                HorizontalDivider()
                NavigationDrawerItem(label = { Text("Media") }, selected = currentScreen == Screen.HOME, onClick = { currentScreen = Screen.HOME; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Rounded.PhotoLibrary, null) })
                NavigationDrawerItem(label = { Text("Recycle Bin") }, selected = currentScreen == Screen.RECYCLE_BIN, onClick = { currentScreen = Screen.RECYCLE_BIN; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Rounded.Delete, null) })

                // BIOMETRIC
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Fingerprint, null, tint = Color.Gray); Spacer(Modifier.width(12.dp)); Text("Biometric Unlock", fontWeight = FontWeight.Medium) }
                    Switch(checked = biometricEnabled, onCheckedChange = { biometricEnabled = it; vaultManager.setBiometricEnabled(it) })
                }

                // SCREENSHOT BLOCKER (NEW)
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.VisibilityOff, null, tint = Color.Gray); Spacer(Modifier.width(12.dp)); Text("Block Screenshots", fontWeight = FontWeight.Medium) }
                    Switch(checked = screenshotBlocked, onCheckedChange = { screenshotBlocked = it; vaultManager.setScreenshotBlockEnabled(it) })
                }

                NavigationDrawerItem(label = { Text("About") }, selected = false, onClick = { showAboutDialog = true; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Rounded.Info, null) })
            }
        }
    ) {
        Scaffold(
            containerColor = PitchBlack,
            topBar = {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isSelectionMode) {
                        IconButton(onClick = { isSelectionMode = false; selectedItems = emptySet() }) { Icon(Icons.Default.Close, null, tint = TextWhite) }
                        Text("${selectedItems.size}", color = TextWhite, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (currentScreen == Screen.RECYCLE_BIN) {
                            IconButton(onClick = { scope.launch { fileManager.restoreFromTrash(displayItems.filter { selectedItems.contains(it.id) }); refresh(); isSelectionMode = false } }) { Icon(Icons.Default.Restore, null, tint = TextWhite) }
                            IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Default.DeleteForever, null, tint = WarningRed) }
                        } else {
                            IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Default.Delete, null, tint = WarningRed) }
                        }
                    } else {
                        if (currentFolderId != null) IconButton(onClick = { currentFolderId = allItems.find { it.id == currentFolderId }?.parentId; refresh() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) }
                        else Image(painter = painterResource(id = R.drawable.vaultx), contentDescription = null, modifier = Modifier.size(32.dp))

                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(50)).background(DarkGray), contentAlignment = Alignment.CenterStart) {
                            BasicTextField(value = searchQuery, onValueChange = { searchQuery = it }, textStyle = TextStyle(color = TextWhite, fontSize = 14.sp), cursorBrush = SolidColor(VioletAccent), singleLine = true, decorationBox = { inner -> if(searchQuery.isEmpty()) Text("Search", color = Color.Gray, fontSize = 12.sp); inner() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                        }

                        Box {
                            IconButton(onClick = { showViewMenu = true }) { Icon(Icons.Rounded.Visibility, null, tint = TextWhite) }
                            DropdownMenu(expanded = showViewMenu, onDismissRequest = { showViewMenu = false }) {
                                DropdownMenuItem(text = { Text("List View", color = TextWhite) }, onClick = { viewMode = ViewMode.LIST; showViewMenu = false }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null, tint = VioletAccent) })
                                DropdownMenuItem(text = { Text("Grid View (4)", color = TextWhite) }, onClick = { viewMode = ViewMode.GRID; showViewMenu = false }, leadingIcon = { Icon(Icons.Rounded.GridView, null, tint = VioletAccent) })
                                DropdownMenuItem(text = { Text("Tiny View (7)", color = TextWhite) }, onClick = { viewMode = ViewMode.TINY; showViewMenu = false }, leadingIcon = { Icon(Icons.Rounded.Apps, null, tint = VioletAccent) })
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Row(verticalAlignment = Alignment.CenterVertically) { Text("Show Dates", color = TextWhite, modifier = Modifier.weight(1f)); Switch(checked = showDates, onCheckedChange = null) } },
                                    onClick = { showDates = !showDates }
                                )
                            }
                        }

                        if (currentScreen == Screen.RECYCLE_BIN) {
                            IconButton(onClick = { showEmptyBinDialog = true }) { Icon(Icons.Rounded.DeleteSweep, null, tint = WarningRed) }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Rounded.Menu, null, tint = TextWhite) }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (currentScreen == Screen.HOME && !isSelectionMode) {
                    Column(horizontalAlignment = Alignment.End) {
                        AnimatedVisibility(visible = isFabExpanded) {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Text("New Album", color = TextWhite, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp).background(Color.Black.copy(0.7f), RoundedCornerShape(4.dp)).padding(4.dp)); FloatingActionButton(onClick = { showCreateAlbumDialog = true; isFabExpanded = false }, containerColor = VioletAccent, shape = CircleShape, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.CreateNewFolder, "Album", tint = Color.White) } }
                                Row(verticalAlignment = Alignment.CenterVertically) { Text("Import", color = TextWhite, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp).background(Color.Black.copy(0.7f), RoundedCornerShape(4.dp)).padding(4.dp)); FloatingActionButton(onClick = { if(!isImporting) launcher.launch(arrayOf("*/*")) }, containerColor = if(isImporting) Color.Gray else VioletAccent, shape = CircleShape, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.AddPhotoAlternate, "Import", tint = Color.White) } }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        FloatingActionButton(onClick = { isFabExpanded = !isFabExpanded }, containerColor = VioletAccent, shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.Add, null, tint = TextWhite, modifier = Modifier.rotate(if (isFabExpanded) 45f else 0f)) }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize().background(PitchBlack)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom > 1.2f && viewMode == ViewMode.TINY) viewMode = ViewMode.GRID
                        else if (zoom < 0.8f && viewMode == ViewMode.GRID) viewMode = ViewMode.TINY
                    }
                }
            ) {
                if (currentScreen == Screen.HOME) {
                    val cols = when(viewMode) {
                        ViewMode.LIST -> 1
                        ViewMode.GRID -> 4
                        ViewMode.TINY -> 7
                    }

                    LazyVerticalGrid(columns = GridCells.Fixed(cols), contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 100.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        groupedItems.forEach { (header, items) ->
                            if (showDates) {
                                item(span = { GridItemSpan(cols) }) { Text(header, color = TextWhite, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                            }
                            items(items, key = { it.id }) { item ->
                                if (viewMode == ViewMode.LIST) {
                                    ListGridItem(item, thumbnailPaths[item.id], selectedItems.contains(item.id),
                                        onClick = {
                                            if (isSelectionMode) selectedItems = if(selectedItems.contains(item.id)) selectedItems - item.id else selectedItems + item.id
                                            else { if (item.type == ItemType.FOLDER) { currentFolderId = item.name; refresh() } else viewerStartIndex = displayItems.indexOf(item) }
                                        },
                                        onLongClick = { if (!isSelectionMode) { selectedItems = setOf(item.id); isSelectionMode = true; showContextMenu = true } }
                                    )
                                } else {
                                    GlassGridItem(item, thumbnailPaths[item.id], selectedItems.contains(item.id),
                                        onClick = {
                                            if (isSelectionMode) selectedItems = if(selectedItems.contains(item.id)) selectedItems - item.id else selectedItems + item.id
                                            else { if (item.type == ItemType.FOLDER) { currentFolderId = item.name; refresh() } else viewerStartIndex = displayItems.indexOf(item) }
                                        },
                                        onLongClick = { if (!isSelectionMode) { selectedItems = setOf(item.id); isSelectionMode = true; showContextMenu = true } }
                                    )
                                }
                            }
                        }
                    }
                } else if (currentScreen == Screen.RECYCLE_BIN) {
                    val deleted = allItems.filter { it.isDeleted }
                    if (deleted.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Empty", color = Color.Gray) }
                    else VaultGrid(deleted, thumbnailPaths, selectedItems, isSelectionMode, 4, { if(isSelectionMode) selectedItems = if (selectedItems.contains(it.id)) selectedItems - it.id else selectedItems + it.id }, { selectedItems = setOf(it.id); isSelectionMode = true })
                } else if (currentScreen == Screen.MAP_VIEW) {
                    AndroidView(factory = { ctx -> Configuration.getInstance().userAgentValue = "VaultX"; MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(4.0); mapMarkers.forEach { m -> if (m.lat != null && m.lon != null) { val marker = Marker(this); marker.position = GeoPoint(m.lat, m.lon); overlays.add(marker) } } } }, modifier = Modifier.fillMaxSize())
                }

                Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isSyncingThumbs && syncProgress < 1.0f) CuteProgressIndicator(text = "Syncing: ${(syncProgress * 100).toInt()}%", progress = syncProgress)
                    if (isImporting) CuteProgressIndicator(text = "Importing Files...", indeterminate = true)
                }
            }
        }
    }

    if (showContextMenu) {
        ModalBottomSheet(onDismissRequest = { showContextMenu = false }, sheetState = bottomSheetState, containerColor = DarkGray) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("${selectedItems.size} Selected", color = TextWhite, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.5f))
                ContextMenuItem(icon = Icons.Rounded.SelectAll, text = "Select All") { selectedItems = displayItems.map { it.id }.toSet(); scope.launch { bottomSheetState.hide() }.invokeOnCompletion { showContextMenu = false } }
                ContextMenuItem(icon = Icons.Rounded.Delete, text = "Delete", tint = WarningRed) { showDeleteDialog = true; scope.launch { bottomSheetState.hide() }.invokeOnCompletion { showContextMenu = false } }
                ContextMenuItem(icon = Icons.Rounded.DriveFileMove, text = "Move to Folder") { showMoveDialog = true; scope.launch { bottomSheetState.hide() }.invokeOnCompletion { showContextMenu = false } }
                ContextMenuItem(icon = Icons.Rounded.Output, text = "Export") { val ids = selectedItems.toTypedArray(); val work = OneTimeWorkRequestBuilder<FileWorker>().setInputData(workDataOf("ACTION" to "EXPORT", "PIN" to userPin, "IDS" to ids)).build(); WorkManager.getInstance(context).enqueue(work); Toast.makeText(context, "Exporting...", Toast.LENGTH_SHORT).show(); isSelectionMode = false; selectedItems = emptySet(); scope.launch { bottomSheetState.hide() }.invokeOnCompletion { showContextMenu = false } }
                ContextMenuItem(icon = Icons.Rounded.Share, text = "Share") { shareSelectedItems(); scope.launch { bottomSheetState.hide() }.invokeOnCompletion { showContextMenu = false } }
            }
        }
    }

    if (viewerStartIndex != null) {
        val viewList = displayItems.filter { it.type != ItemType.FOLDER }
        MediaViewer(viewerStartIndex!!, viewList, userPin) { viewerStartIndex = null }
    }

    // ... Dialogs, ListGridItem, GlassGridItem, VaultGrid (Same as previous) ...
    if (showCreateAlbumDialog) { var name by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = { showCreateAlbumDialog = false }, title = { Text("New Album") }, text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }) }, confirmButton = { Button(onClick = { if (name.isNotEmpty()) { fileManager.createFolder(name, currentFolderId); refresh(); showCreateAlbumDialog = false } }) { Text("Create") } }) }
    if (showDeleteDialog) { AlertDialog(onDismissRequest = { showDeleteDialog = false }, title = { Text("Delete Forever?") }, confirmButton = { Button(onClick = { scope.launch { val nuke = displayItems.filter { selectedItems.contains(it.id) }; if(currentScreen==Screen.RECYCLE_BIN) fileManager.deletePermanently(nuke) else fileManager.moveToTrash(nuke); refresh(); isSelectionMode = false; showDeleteDialog = false } }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Delete") } }, dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }) }
    if (showEmptyBinDialog) { AlertDialog(onDismissRequest = { showEmptyBinDialog = false }, title = { Text("Empty Bin?") }, text = { Text("Permanently delete all items in trash?") }, confirmButton = { Button(onClick = { scope.launch { val nuke = allItems.filter { it.isDeleted }; fileManager.deletePermanently(nuke); refresh(); showEmptyBinDialog = false } }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Empty") } }, dismissButton = { TextButton(onClick = { showEmptyBinDialog = false }) { Text("Cancel") } }) }
    if (showMoveDialog) { val folders = allItems.filter { it.type == ItemType.FOLDER && !selectedItems.contains(it.id) }; AlertDialog(onDismissRequest = { showMoveDialog = false }, title = { Text("Move to Folder") }, text = { LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) { item { ListItem(headlineContent = { Text("Root Folder") }, leadingContent = { Icon(Icons.Default.Home, null) }, modifier = Modifier.clickable { fileManager.moveItems(displayItems.filter { selectedItems.contains(it.id) }, null, userPin); refresh(); showMoveDialog = false; isSelectionMode = false }) }; items(folders) { folder -> ListItem(headlineContent = { Text(folder.name) }, leadingContent = { Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { fileManager.moveItems(displayItems.filter { selectedItems.contains(it.id) }, folder.id, userPin); refresh(); showMoveDialog = false; isSelectionMode = false }) } } }, confirmButton = { TextButton(onClick = { showMoveDialog = false }) { Text("Cancel") } }) }
    if (showAboutDialog) { AlertDialog(onDismissRequest = { showAboutDialog = false }, title = { Text("About VaultX") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Image(painter = painterResource(id = R.drawable.vaultx), contentDescription = null, modifier = Modifier.size(64.dp)); Spacer(Modifier.height(8.dp)); Text("VaultX v1.1", fontWeight = FontWeight.Bold); Text("Secure Offline Vault", fontSize = 12.sp, color = Color.Gray); Spacer(Modifier.height(16.dp)); Row(modifier = Modifier.clickable { val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Midxv/VaultX")); context.startActivity(i) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Code, null, tint = VioletAccent); Spacer(Modifier.width(8.dp)); Text("View Source on GitHub", color = VioletAccent) } } }, confirmButton = { TextButton(onClick = { showAboutDialog = false }) { Text("Close") } }) }
}

@Composable
fun CuteProgressIndicator(text: String, progress: Float = 0f, indeterminate: Boolean = false) { Card(shape = RoundedCornerShape(50), colors = CardDefaults.cardColors(containerColor = VioletAccent), elevation = CardDefaults.cardElevation(8.dp)) { Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { if (indeterminate) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TextWhite, strokeWidth = 2.dp) else CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(20.dp), color = TextWhite, strokeWidth = 2.dp, trackColor = Color.White.copy(alpha = 0.3f)); Spacer(Modifier.width(12.dp)); Text(text, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium) } } }
@Composable
fun ContextMenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color = TextWhite, onClick: () -> Unit) { Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(24.dp)); Text(text, color = tint, fontSize = 16.sp) } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListGridItem(item: VaultItem, thumbPath: String?, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) VioletAccent.copy(alpha = 0.3f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(DarkGray)) {
            if (thumbPath != null && File(thumbPath).exists()) AsyncImage(model = File(thumbPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(if (item.type == ItemType.FOLDER) Icons.Rounded.Folder else Icons.Default.Image, null, tint = Color.Gray, modifier = Modifier.align(Alignment.Center).size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(item.name, color = TextWhite, fontWeight = FontWeight.Medium)
            Text(SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(item.dateModified)), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassGridItem(item: VaultItem, thumbPath: String?, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(DarkGray).border(if(isSelected) 2.dp else 0.dp, VioletAccent, RoundedCornerShape(12.dp))) {
            if (thumbPath != null && File(thumbPath).exists()) AsyncImage(model = File(thumbPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(if (item.type == ItemType.FOLDER) Icons.Rounded.Folder else Icons.Default.Image, null, tint = Color.Gray, modifier = Modifier.align(Alignment.Center).size(48.dp))
            if (item.type == ItemType.VIDEO) Icon(Icons.Default.PlayArrow, null, tint = TextWhite, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(4.dp))
        Text(text = item.name, color = TextWhite, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultGrid(items: List<VaultItem>, thumbMap: Map<String, String>, selectedItems: Set<String>, isSelectionMode: Boolean, columns: Int, onItemClick: (VaultItem) -> Unit, onLongClick: (VaultItem) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(columns), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { item ->
            GlassGridItem(item, thumbMap[item.id], selectedItems.contains(item.id), { if(isSelectionMode) onItemClick(item) else onItemClick(item) }, { onLongClick(item) })
        }
    }
}