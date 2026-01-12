package vault.x.org

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.floor

enum class Screen { HOME, RECYCLE_BIN, BROWSER, DUPLICATES, SETTINGS, AI_FILTER }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(userPin: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val crypto = remember { CryptoManager(context) }
    val indexManager = remember { IndexManager(context, crypto) }
    val vaultManager = remember { VaultManager(context) }
    val thumbManager = remember { ThumbnailManager(context, crypto) }
    val aiScanner = remember { AiScanner(context) }

    // STATE
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var aiFilterTag by remember { mutableStateOf<String?>(null) }

    var allItems by remember { mutableStateOf(listOf<VaultItem>()) }
    var selectedItems by remember { mutableStateOf(setOf<String>()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    var thumbnailPaths by remember { mutableStateOf(mapOf<String, String>()) }
    var processedThumbs by remember { mutableStateOf(0) }
    var totalThumbs by remember { mutableStateOf(0) }

    var gridColumns by remember { mutableIntStateOf(4) }
    var sortOption by remember { mutableStateOf(SortOption.DATE_NEW) }
    var showSortMenu by remember { mutableStateOf(false) }

    // DIALOGS
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<VaultItem?>(null) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var itemForMenu by remember { mutableStateOf<VaultItem?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showPostExportDeleteDialog by remember { mutableStateOf<VaultItem?>(null) }

    // LOADING
    var isImporting by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingText by remember { mutableStateOf("") }
    var aiProgress by remember { mutableStateOf("") }
    var viewerStartIndex by remember { mutableStateOf<Int?>(null) }
    var duplicateGroups by remember { mutableStateOf<Map<String, List<VaultItem>>?>(null) }

    // --- SYSTEM DELETE HANDLER (FIXED) ---
    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context, "Originals Deleted", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteOriginals(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // Request system permission to delete
                val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                intentSenderLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Could not launch delete dialog", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Android 10 and below
            try {
                var deletedCount = 0
                uris.forEach { uri ->
                    if(context.contentResolver.delete(uri, null, null) > 0) deletedCount++
                }
                Toast.makeText(context, "Deleted $deletedCount files", Toast.LENGTH_SHORT).show()
            } catch (e: RecoverableSecurityException) {
                // On Android 10, catch this to show system dialog
                intentSenderLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            }
        }
    }

    // --- REFRESH LOGIC ---
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            indexManager.loadIndex(userPin)
            val items = indexManager.index.items
            withContext(Dispatchers.Main) { allItems = items }
            thumbManager.syncThumbnails(userPin, items,
                onProgress = { p, t -> processedThumbs = p; totalThumbs = t },
                onUpdate = { map -> thumbnailPaths = map }
            )
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        if (vaultManager.isAiEnabled()) {
            withContext(Dispatchers.IO) {
                val unscanned = indexManager.index.items.filter { it.type == ItemType.IMAGE && !it.isAiProcessed }
                var count = 0
                unscanned.forEach { item ->
                    aiProgress = "AI: $count/${unscanned.size}"
                    val file = crypto.decryptToCache(context, userPin, item.id, "jpg")
                    if (file != null) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            item.tags = aiScanner.scanImage(bitmap)
                            item.isAiProcessed = true
                            file.delete()
                        }
                    }
                    count++
                    if (count % 5 == 0) indexManager.saveIndex(userPin)
                }
                if (count > 0) indexManager.saveIndex(userPin)
                aiProgress = ""
            }
        }
    }

    fun scanDuplicates() {
        isProcessing = true; processingText = "Scanning..."
        scope.launch(Dispatchers.IO) {
            val potential = allItems.filter { !it.isDeleted && it.type != ItemType.FOLDER }.groupBy { "${it.name}_${it.size}" }.filter { it.value.size > 1 }
            val real = mutableMapOf<String, MutableList<VaultItem>>()
            potential.values.forEach { group ->
                val hashes = group.groupBy { crypto.calculateMD5(it.id) }
                hashes.forEach { (h, i) -> if (i.size > 1 && h.isNotEmpty()) real[h] = i.toMutableList() }
            }
            withContext(Dispatchers.Main) { duplicateGroups = real; isProcessing = false }
        }
    }

    fun getFolderName() = if (currentFolderId == null) "VaultX" else allItems.find { it.id == currentFolderId }?.name ?: "Folder"

    // --- NAVIGATION BACK HANDLER ---
    BackHandler {
        when {
            isSelectionMode -> { isSelectionMode = false; selectedItems = emptySet() }
            currentScreen == Screen.AI_FILTER -> { aiFilterTag = null; currentScreen = Screen.HOME }
            currentScreen != Screen.HOME -> currentScreen = Screen.HOME
            currentFolderId != null -> currentFolderId = allItems.find { it.id == currentFolderId }?.parentId
            searchQuery.isNotEmpty() -> searchQuery = ""
            else -> (context as? Activity)?.finish()
        }
    }

    if (currentScreen == Screen.BROWSER) {
        BrowserScreen(userPin) { currentScreen = Screen.HOME; refresh() }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(32.dp))
                // LOGO IN DRAWER TOO
                Image(painter = painterResource(id = R.drawable.vaultx), contentDescription = null, modifier = Modifier.padding(16.dp).size(64.dp))
                Text("VaultX", modifier = Modifier.padding(start = 16.dp, bottom = 16.dp), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                HorizontalDivider()
                NavigationDrawerItem(label = { Text("Home") }, selected = currentScreen == Screen.HOME, onClick = { currentScreen = Screen.HOME; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Default.Home, null) })
                NavigationDrawerItem(label = { Text("Recycle Bin") }, selected = currentScreen == Screen.RECYCLE_BIN, onClick = { currentScreen = Screen.RECYCLE_BIN; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Default.Delete, null) })
                NavigationDrawerItem(label = { Text("Private Browser") }, selected = currentScreen == Screen.BROWSER, onClick = { currentScreen = Screen.BROWSER; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Default.Search, null) })
                NavigationDrawerItem(label = { Text("Duplicate Finder") }, selected = currentScreen == Screen.DUPLICATES, onClick = { currentScreen = Screen.DUPLICATES; scanDuplicates(); scope.launch { drawerState.close() } }, icon = { Icon(Icons.Default.List, null) })
                NavigationDrawerItem(label = { Text("Settings") }, selected = currentScreen == Screen.SETTINGS, onClick = { currentScreen = Screen.SETTINGS; scope.launch { drawerState.close() } }, icon = { Icon(Icons.Default.Settings, null) })
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.background(Color.Black).statusBarsPadding(),
            topBar = {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                    Row(
                        Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSelectionMode) {
                            IconButton(onClick = { isSelectionMode = false; selectedItems = emptySet() }) { Icon(Icons.Default.Close, "Close") }
                            Text("${selectedItems.size}", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))

                            // SELECTION MENU (Right Side)
                            IconButton(onClick = {
                                selectedItems = if(selectedItems.size == allItems.filter { it.parentId == currentFolderId }.size) emptySet() else allItems.filter { it.parentId == currentFolderId }.map { it.id }.toSet()
                            }) { Icon(Icons.Default.SelectAll, "All") }

                            // ACTIONS ROW
                            Row {
                                if (currentScreen == Screen.RECYCLE_BIN) {
                                    IconButton(onClick = { allItems.filter { selectedItems.contains(it.id) }.forEach { it.isDeleted = false }; indexManager.saveIndex(userPin); refresh(); isSelectionMode = false }) { Icon(Icons.Default.Restore, "Restore") }
                                    IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Default.DeleteForever, "Delete", tint = Color.Red) }
                                } else {
                                    IconButton(onClick = { showMoveDialog = true }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Move") }
                                    IconButton(onClick = {
                                        // Share Logic
                                        isProcessing = true
                                        scope.launch(Dispatchers.IO) {
                                            val files = allItems.filter { selectedItems.contains(it.id) }.mapNotNull { item ->
                                                crypto.decryptToCache(context, userPin, item.id, item.name.substringAfterLast('.', "dat"))
                                            }
                                            if (files.isNotEmpty()) {
                                                val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.provider", it) }
                                                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                                    type = "*/*"
                                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Share"))
                                            }
                                            withContext(Dispatchers.Main) { isProcessing = false; isSelectionMode = false }
                                        }
                                    }) { Icon(Icons.Default.Share, "Share") }

                                    IconButton(onClick = {
                                        // EXPORT
                                        isProcessing = true
                                        scope.launch(Dispatchers.IO) {
                                            val downDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                            allItems.filter { selectedItems.contains(it.id) }.forEach { item ->
                                                val dest = File(downDir, item.name)
                                                crypto.decryptToStream(userPin, item.id, FileOutputStream(dest))
                                            }
                                            withContext(Dispatchers.Main) {
                                                isProcessing = false
                                                showPostExportDeleteDialog = allItems.find { it.id == selectedItems.first() } // Trigger delete prompt for batch
                                                isSelectionMode = false
                                            }
                                        }
                                    }) { Icon(Icons.Default.Output, "Export") } // Using Output for Export

                                    IconButton(onClick = {
                                        allItems.filter { selectedItems.contains(it.id) }.forEach { it.isDeleted = true; it.deletedTimestamp = System.currentTimeMillis() }
                                        indexManager.saveIndex(userPin); refresh(); isSelectionMode = false
                                    }) { Icon(Icons.Default.Delete, "Delete") }
                                }
                            }
                        } else {
                            if (currentFolderId != null && currentScreen == Screen.HOME) {
                                IconButton(onClick = { currentFolderId = allItems.find { it.id == currentFolderId }?.parentId }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                            } else {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "Menu") }
                            }

                            if (currentScreen == Screen.HOME && aiFilterTag == null) {
                                Box(
                                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (searchQuery.isEmpty()) Text("Search...", color = Color.Gray, fontSize = 14.sp)
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        singleLine = true,
                                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else {
                                Text(when(currentScreen){ Screen.RECYCLE_BIN -> "Trash"; Screen.DUPLICATES -> "Duplicates"; Screen.AI_FILTER -> "AI: $aiFilterTag"; else -> "Settings" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            }
                        }

                        if (currentScreen == Screen.HOME && !isSelectionMode && aiFilterTag == null) {
                            Box {
                                IconButton(onClick = { showSortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) { SortOption.values().forEach { o -> DropdownMenuItem(text = { Text(o.name) }, onClick = { sortOption = o; showSortMenu = false }) } }
                            }
                            // IMPORT
                            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                                if (uris.isNotEmpty()) {
                                    isImporting = true
                                    scope.launch(Dispatchers.IO) {
                                        uris.forEach { uri ->
                                            try {
                                                val meta = getFileMetadata(context, uri)
                                                val newItem = VaultItem(name = meta.first, type = detectMimeType(context, uri), parentId = currentFolderId, size = meta.second)
                                                context.contentResolver.openInputStream(uri)?.use { crypto.encryptData(userPin, it, newItem.id) {} }
                                                withContext(Dispatchers.Main) { indexManager.addFile(newItem) }
                                            } catch (e: Exception) {}
                                        }
                                        withContext(Dispatchers.Main) {
                                            indexManager.saveIndex(userPin)
                                            isImporting = false
                                            refresh()
                                            // TRIGGER SYSTEM DELETE PROMPT
                                            deleteOriginals(uris)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            FilledIconButton(onClick = { launcher.launch(arrayOf("*/*")) }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.Default.Add, "Import") }
                        }
                    }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(visible = !isSelectionMode && currentScreen == Screen.HOME && aiFilterTag == null, enter = fadeIn(), exit = fadeOut()) {
                    FloatingActionButton(onClick = { showCreateAlbumDialog = true }, shape = CircleShape, containerColor = MaterialTheme.colorScheme.secondary) { Icon(Icons.Default.CreateNewFolder, "New Album") }
                }
            }
        ) { padding ->
            // GESTURE DETECTOR FOR GRID RESIZING
            Box(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom > 1.2f) gridColumns = 3 // Zoom In -> Fewer cols
                        else if (zoom < 0.8f) gridColumns = 7 // Zoom Out -> More cols
                        else if (zoom > 0.9f && zoom < 1.1f) gridColumns = 4 // Reset
                    }
                }
            ) {
                Column {
                    Box(Modifier.weight(1f)) {
                        when(currentScreen) {
                            Screen.HOME, Screen.AI_FILTER -> {
                                var displayItems = allItems.filter { !it.isDeleted }
                                if (currentScreen == Screen.AI_FILTER && aiFilterTag != null) {
                                    displayItems = displayItems.filter { it.tags.any { tag -> tag.contains(aiFilterTag!!, ignoreCase = true) } }
                                } else {
                                    displayItems = displayItems.filter { it.parentId == currentFolderId }
                                        .filter { searchQuery.isEmpty() || it.name.contains(searchQuery, true) || it.tags.any { t -> t.contains(searchQuery, true) } }
                                }
                                displayItems = displayItems.sortedWith(when(sortOption) {
                                    SortOption.DATE_NEW -> compareByDescending { it.dateModified }
                                    SortOption.DATE_OLD -> compareBy { it.dateModified }
                                    SortOption.NAME_AZ -> compareBy { it.name }
                                    SortOption.NAME_ZA -> compareByDescending { it.name }
                                })

                                Column {
                                    VaultGrid(displayItems, allItems, thumbnailPaths, selectedItems, isSelectionMode, gridColumns,
                                        onItemClick = { item ->
                                            if (isSelectionMode) selectedItems = if (selectedItems.contains(item.id)) selectedItems - item.id else selectedItems + item.id
                                            else {
                                                if (item.type == ItemType.FOLDER) currentFolderId = item.id
                                                else {
                                                    // HANDLE EXTERNAL OPENING
                                                    if (item.type == ItemType.PDF || item.type == ItemType.AUDIO) {
                                                        scope.launch(Dispatchers.IO) {
                                                            val ext = item.name.substringAfterLast('.', "dat")
                                                            val file = crypto.decryptToCache(context, userPin, item.id, ext)
                                                            if (file != null) {
                                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                                    val mime = if (item.type == ItemType.PDF) "application/pdf" else "audio/*"
                                                                    setDataAndType(uri, mime)
                                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                                }
                                                                try { context.startActivity(intent) } catch(e: Exception) {
                                                                    withContext(Dispatchers.Main){ Toast.makeText(context, "No app found", Toast.LENGTH_SHORT).show() }
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        viewerStartIndex = displayItems.indexOf(item)
                                                    }
                                                }
                                            }
                                        },
                                        onLongClick = { selectedItems = setOf(it.id); isSelectionMode = true }
                                    )

                                    if (currentScreen == Screen.HOME && currentFolderId == null && !isSelectionMode) {
                                        Text("AI Tools", Modifier.padding(start = 16.dp, top = 8.dp), fontWeight = FontWeight.Bold, color = Color.Gray)
                                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                                            AiToolCard("Selfies", Icons.Default.Face) { aiFilterTag = "Selfie"; currentScreen = Screen.AI_FILTER }
                                            AiToolCard("Docs", Icons.Default.Info) { aiFilterTag = "Text"; currentScreen = Screen.AI_FILTER }
                                            AiToolCard("Places", Icons.Default.LocationOn) { aiFilterTag = "Landmark"; currentScreen = Screen.AI_FILTER }
                                            AiToolCard("Duplicates", Icons.Default.List) { currentScreen = Screen.DUPLICATES; scanDuplicates() }
                                        }
                                    }
                                }
                            }

                            Screen.RECYCLE_BIN -> {
                                val deleted = allItems.filter { it.isDeleted }
                                if (deleted.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Trash Empty") }
                                else VaultGrid(deleted, allItems, thumbnailPaths, selectedItems, isSelectionMode, gridColumns, { if(isSelectionMode) selectedItems = if (selectedItems.contains(it.id)) selectedItems - it.id else selectedItems + it.id }, { selectedItems = setOf(it.id); isSelectionMode = true })
                            }

                            Screen.DUPLICATES -> {
                                if (isProcessing) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                else {
                                    Column {
                                        Button(onClick = { scanDuplicates() }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp)) { Text("Start Scan") }
                                        if (duplicateGroups.isNullOrEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No Duplicates Found") }
                                        else {
                                            LazyVerticalGrid(columns = GridCells.Fixed(1)) {
                                                duplicateGroups!!.forEach { (_, items) ->
                                                    item {
                                                        Card(Modifier.padding(8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                                            Column(Modifier.padding(12.dp)) {
                                                                Text("Duplicate Group (${items.size})", fontWeight = FontWeight.Bold)
                                                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                                                    items.forEach { item ->
                                                                        val thumb = thumbnailPaths[item.id]?.let { File(it) }
                                                                        if(thumb != null) AsyncImage(model = thumb, contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Color.Gray, RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                                                        Spacer(Modifier.width(4.dp))
                                                                    }
                                                                }
                                                                Button(onClick = { items.drop(1).forEach { it.isDeleted = true }; indexManager.saveIndex(userPin); refresh(); scanDuplicates() }, modifier = Modifier.align(Alignment.End)) { Text("Keep Best") }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Screen.SETTINGS -> {
                                Column(Modifier.padding(16.dp)) {
                                    var isBio by remember { mutableStateOf(vaultManager.isBiometricEnabled()) }
                                    var isAi by remember { mutableStateOf(vaultManager.isAiEnabled()) }
                                    val aiStats = allItems.count { it.type == ItemType.IMAGE && it.isAiProcessed }
                                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Biometric Unlock", Modifier.weight(1f)); Switch(isBio, { isBio = it; vaultManager.setBiometricEnabled(it) }) }
                                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("AI Search");
                                            Text("Processed: $aiStats", fontSize = 10.sp, color = Color.Gray)
                                        }
                                        Switch(isAi, { isAi = it; vaultManager.setAiEnabled(it) })
                                    }
                                }
                            }
                            else -> {}
                        }
                    }

                    if ((processedThumbs < totalThumbs) || aiProgress.isNotEmpty()) {
                        Surface(color = Color.Black.copy(0.9f), contentColor = Color.White) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                                if (processedThumbs < totalThumbs) Text("Syncing: $processedThumbs/$totalThumbs", fontSize = 12.sp)
                                if (processedThumbs < totalThumbs && aiProgress.isNotEmpty()) Spacer(Modifier.width(16.dp))
                                if (aiProgress.isNotEmpty()) Text(aiProgress, fontSize = 12.sp)
                            }
                        }
                    }
                }

                if (isImporting || (isProcessing && currentScreen != Screen.DUPLICATES)) Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }

    if (viewerStartIndex != null) {
        val viewList = allItems.filter { !it.isDeleted && (if(currentScreen == Screen.HOME) it.parentId == currentFolderId else true) }
        MediaViewer(viewerStartIndex!!, viewList, userPin) { viewerStartIndex = null }
    }

    // ... [Standard Dialogs: Create, Rename, Move, PostExportDelete - Include logic from previous steps] ...
    if (showCreateAlbumDialog) { var name by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = { showCreateAlbumDialog = false }, title = { Text("New Album") }, text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }) }, confirmButton = { Button(onClick = { if (name.isNotEmpty()) { indexManager.createFolder(name, currentFolderId); indexManager.saveIndex(userPin); refresh(); showCreateAlbumDialog = false } }) { Text("Create") } }) }
    if (showDeleteDialog) { AlertDialog(onDismissRequest = { showDeleteDialog = false }, title = { Text("Delete Forever?") }, confirmButton = { Button(onClick = { val nuke = allItems.filter { selectedItems.contains(it.id) }; indexManager.deleteItems(nuke, userPin); refresh(); isSelectionMode = false; showDeleteDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Delete") } }, dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }) }
    if (showRenameDialog != null) { var name by remember { mutableStateOf(showRenameDialog!!.name) }; AlertDialog(onDismissRequest = { showRenameDialog = null }, title = { Text("Rename") }, text = { OutlinedTextField(value = name, onValueChange = { name = it }) }, confirmButton = { Button(onClick = { indexManager.renameItem(showRenameDialog!!, name, userPin); refresh(); isSelectionMode = false; selectedItems = emptySet(); showRenameDialog = null }) { Text("Save") } }) }
    if (showMoveDialog) { val folders = allItems.filter { it.type == ItemType.FOLDER && !selectedItems.contains(it.id) }; AlertDialog(onDismissRequest = { showMoveDialog = false }, title = { Text("Move to...") }, text = { Column { if (currentFolderId != null) TextButton(onClick = { indexManager.moveItems(allItems.filter { selectedItems.contains(it.id) }, null, userPin); refresh(); showMoveDialog = false; isSelectionMode = false }) { Text("Root") }; folders.forEach { f -> TextButton(onClick = { indexManager.moveItems(allItems.filter { selectedItems.contains(it.id) }, f.id, userPin); refresh(); showMoveDialog = false; isSelectionMode = false }) { Text(f.name) } } } }, confirmButton = { TextButton(onClick = { showMoveDialog = false }) { Text("Cancel") } }) }
    if (showPostExportDeleteDialog != null) { AlertDialog(onDismissRequest = { showPostExportDeleteDialog = null }, title = { Text("Exported") }, text = { Text("Delete from Vault?") }, confirmButton = { Button(onClick = { indexManager.deleteItems(listOf(showPostExportDeleteDialog!!), userPin); showPostExportDeleteDialog = null; refresh() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Delete") } }, dismissButton = { TextButton(onClick = { showPostExportDeleteDialog = null }) { Text("Keep") } }) }
}

@Composable
fun AiToolCard(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(modifier = Modifier.padding(4.dp).clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultGrid(
    items: List<VaultItem>,
    allItems: List<VaultItem>,
    thumbMap: Map<String, String>,
    selectedItems: Set<String>,
    isSelectionMode: Boolean,
    columns: Int,
    onItemClick: (VaultItem) -> Unit,
    onLongClick: (VaultItem) -> Unit
) {
    LazyVerticalGrid(columns = GridCells.Fixed(columns), contentPadding = PaddingValues(4.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            // SNAKE ANIMATION
            val isVisible = remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(index * 20L); isVisible.value = true }

            val isSelected = selectedItems.contains(item.id)
            var modelData: Any? = null
            if (item.type == ItemType.FOLDER) {
                val last = allItems.filter { it.parentId == item.id && (it.type == ItemType.IMAGE || it.type == ItemType.VIDEO) }.maxByOrNull { it.dateModified }
                if (last != null) modelData = thumbMap[last.id]?.let { File(it) }
            } else {
                modelData = thumbMap[item.id]?.let { File(it) }
            }

            AnimatedVisibility(
                visible = isVisible.value,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { 50 },
                modifier = Modifier.animateItemPlacement()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.combinedClickable(onClick = { onItemClick(item) }, onLongClick = { onLongClick(item) })) {
                    Box(Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant).border(if (isSelected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))) {
                        if (modelData != null) {
                            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(modelData).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (item.type == ItemType.VIDEO) Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp).align(Alignment.Center))
                        } else {
                            val icon = when(item.type) {
                                ItemType.FOLDER -> Icons.Default.Lock
                                ItemType.AUDIO -> Icons.Default.MusicNote
                                ItemType.PDF -> Icons.Default.Description
                                else -> Icons.Default.InsertDriveFile
                            }
                            Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(32.dp).align(Alignment.Center))
                        }
                        if (item.type == ItemType.FOLDER) Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(16.dp).align(Alignment.BottomEnd).padding(2.dp))
                    }
                    Text(item.name, maxLines = 1, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }
}

// Helpers
fun getPathFromUri(context: Context, uri: Uri): String? { try { val cursor = context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null); cursor?.use { if (it.moveToFirst()) return it.getString(0) } } catch(e: Exception) {}; return null }
fun getFileMetadata(context: Context, uri: Uri): Pair<String, Long> { var name = "Unknown"; var size = 0L; context.contentResolver.query(uri, null, null, null, null)?.use { cursor -> if (cursor.moveToFirst()) { val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = cursor.getColumnIndex(OpenableColumns.SIZE); if (ni != -1) name = cursor.getString(ni); if (si != -1) size = cursor.getLong(si) } }; return Pair(name, size) }
fun detectMimeType(context: Context, uri: Uri): ItemType { val type = context.contentResolver.getType(uri) ?: return ItemType.UNKNOWN; return when { type.startsWith("image/") -> ItemType.IMAGE; type.startsWith("video/") -> ItemType.VIDEO; type.startsWith("audio/") -> ItemType.AUDIO; type.contains("pdf") -> ItemType.PDF; else -> ItemType.UNKNOWN } }