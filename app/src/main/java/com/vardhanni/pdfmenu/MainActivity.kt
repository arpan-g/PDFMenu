package com.vardhanni.pdfmenu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.pdf.viewer.fragment.PdfViewerFragment
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vardhanni.pdfmenu.data.PdfRepositoryImpl
import com.vardhanni.pdfmenu.ui.PdfUiEvent
import com.vardhanni.pdfmenu.ui.PdfViewModel
import com.vardhanni.pdfmenu.ui.theme.DeepBlack
import com.vardhanni.pdfmenu.ui.theme.DeepBlue
import com.vardhanni.pdfmenu.ui.theme.GlassBorder
import com.vardhanni.pdfmenu.ui.theme.GlassWhite
import com.vardhanni.pdfmenu.ui.theme.PDFMenuTheme
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var rewardedAd: RewardedAd? = null
    private var lastGrantedUri: Uri? = null

    private companion object {
        const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/9214589741"
        const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    }

    private val viewModel: PdfViewModel by viewModels {
        PdfViewModel.Factory(PdfRepositoryImpl(contentResolver, cacheDir))
    }

    private var fragmentContainerId by mutableIntStateOf(View.NO_ID)

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val fileName = getFileName(it)
            clearPdfViewer()
            viewModel.onFilePicked(it, fileName)
        }
    }

    private val savePdfLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let {
            viewModel.saveUnprotected(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PDFBoxResourceLoader.init(applicationContext)
        initializeAds()
        loadRewardedAd()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvents.collect { event ->
                    when (event) {
                        is PdfUiEvent.ShowPdf -> showPdf(event.file)
                        is PdfUiEvent.ShowError -> Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG).show()
                        is PdfUiEvent.Toast -> Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                        is PdfUiEvent.ActionSuccess -> { }
                    }
                }
            }
        }

        setContent {
            PDFMenuTheme {
                PdfMenuScreen(viewModel)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun PdfMenuScreen(viewModel: PdfViewModel) {
        var passwordInput by remember { mutableStateOf("") }
        var lockPasswordInput by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }
        var showToolsSheet by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState()
        
        val containerId = remember { View.generateViewId() }
        val pulseTransition = rememberInfiniteTransition(label = "openButtonPulse")
        val openButtonScale by pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "openButtonScale"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(DeepBlue, DeepBlack)
                    )
                )
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BannerAd(modifier = Modifier.fillMaxWidth())
                    }
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // PDF Preview Card
                    Card(
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth()
                            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = GlassWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            AndroidView(
                                factory = { context ->
                                    FragmentContainerView(context).apply {
                                        id = containerId
                                        fragmentContainerId = containerId
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(24.dp))
                                    .alpha(if (viewModel.isSaveVisible) 1f else 0f)
                            )

                            if (!viewModel.isSaveVisible) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "PDF Preview Area",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Use this zone to visualize and review tool outputs.\n\nNote: This is a preview intended for quick verification. For a superior reading experience, we recommend using a dedicated PDF reader application.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.alpha(0.8f)
                                    )
                                }
                            }
                        }
                    }

                    // Action Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, GlassBorder, RoundedCornerShape(32.dp)),
                        shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.cardColors(containerColor = GlassWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Button(
                                onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .scale(if (viewModel.isSaveVisible) 1f else openButtonScale),
                                shape = RoundedCornerShape(18.dp),
                                contentPadding = PaddingValues(vertical = 16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Choose PDF", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }

                            AnimatedVisibility(
                                visible = viewModel.isSaveVisible,
                                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = { showToolsSheet = true },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiary,
                                            contentColor = MaterialTheme.colorScheme.onTertiary
                                        ),
                                        contentPadding = PaddingValues(vertical = 12.dp)
                                    ) {
                                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Tools")
                                    }

                                    Button(
                                        onClick = { 
                                            showAdIfNecessary { 
                                                val name = viewModel.currentFileName?.removeSuffix(".pdf") ?: "document"
                                                val isCompressed = viewModel.tempPdfFile?.name?.contains("compressed") == true
                                                val isLocked = viewModel.tempPdfFile?.name?.contains("locked") == true
                                                val isUnlocked = viewModel.tempPdfFile?.name?.contains("temp_render") == true
                                                
                                                val suffix = when {
                                                    isCompressed -> "_compressed"
                                                    isLocked -> "_protected"
                                                    isUnlocked -> "_unlocked"
                                                    else -> "_processed"
                                                }
                                                savePdfLauncher.launch("$name$suffix.pdf")
                                            } 
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = viewModel.isSaveEnabled,
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary,
                                            contentColor = MaterialTheme.colorScheme.onSecondary,
                                            disabledContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
                                            disabledContentColor = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.3f)
                                        ),
                                        contentPadding = PaddingValues(vertical = 12.dp)
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Save")
                                    }
                                }
                            }

                            if (!viewModel.isSaveVisible) {
                                Text(
                                    text = "Ready to process your documents",
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Tools Bottom Sheet
        if (showToolsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showToolsSheet = false },
                sheetState = sheetState,
                containerColor = DeepBlue,
                contentColor = Color.White,
                scrimColor = Color.Black.copy(alpha = 0.6f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp)
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "PDF Tools",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    
                    ToolItem(
                        icon = Icons.Default.LockOpen,
                        title = "Unlock PDF",
                        description = "Permanently remove password protection from this file",
                        enabled = viewModel.wasEncrypted,
                        onClick = {
                            showToolsSheet = false
                            val name = viewModel.currentFileName?.removeSuffix(".pdf") ?: "document"
                            savePdfLauncher.launch("${name}_unlocked.pdf")
                        }
                    )

                    ToolItem(
                        icon = Icons.Default.Lock,
                        title = "Lock PDF",
                        description = "Apply a secure password to protect this document",
                        enabled = true,
                        onClick = {
                            showToolsSheet = false
                            viewModel.showLockDialog = true
                        }
                    )

                    ToolItem(
                        icon = Icons.Default.Speed,
                        title = "Compress PDF",
                        description = "Reduce file size by optimizing internal images",
                        enabled = true,
                        onClick = {
                            showToolsSheet = false
                            viewModel.onCompressDialogOpened()
                            viewModel.showCompressDialog = true
                        }
                    )
                }
            }
        }

        if (viewModel.showPasswordDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.showPasswordDialog = false },
                title = { Text(if (viewModel.isRetryPassword) "Incorrect Password" else "Enter Password") },
                text = {
                    TextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                        },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val pass = passwordInput
                        passwordInput = ""
                        viewModel.showPasswordDialog = false
                        viewModel.currentPdfUri?.let { viewModel.processPdf(it, pass) }
                    }) { Text("Open") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.showPasswordDialog = false
                        passwordInput = ""
                    }) { Text("Cancel") }
                }
            )
        }

        if (viewModel.showLockDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.showLockDialog = false },
                title = { Text("Set PDF Password") },
                text = {
                    TextField(
                        value = lockPasswordInput,
                        onValueChange = { lockPasswordInput = it },
                        label = { Text("Password") },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                        },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.lockPdf(lockPasswordInput)
                            lockPasswordInput = ""
                        },
                        enabled = lockPasswordInput.isNotEmpty() && !viewModel.isLocking
                    ) {
                        if (viewModel.isLocking) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        else Text("Lock")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.showLockDialog = false; lockPasswordInput = "" }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (viewModel.showCompressDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.showCompressDialog = false },
                title = { Text("Compress PDF") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Quality: ${(viewModel.compressionQuality * 100).toInt()}%", modifier = Modifier.fillMaxWidth())
                        Slider(
                            value = viewModel.compressionQuality,
                            onValueChange = { viewModel.onCompressQualitySliderChange(it) },
                            onValueChangeFinished = { viewModel.startCompressionCalculation() },
                            valueRange = 0.1f..1.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (viewModel.isCompressing) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Calculating size...", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Text("Estimated Size: ${formatFileSize(viewModel.compressedFileSize)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showAdIfNecessary { viewModel.applyCompression() } },
                        enabled = !viewModel.isCompressing
                    ) { Text("Apply") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.showCompressDialog = false }) { Text("Cancel") }
                }
            )
        }
    }

    @Composable
    private fun ToolItem(
        icon: ImageVector,
        title: String,
        description: String,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        val alpha = if (enabled) 1f else 0.4f
        Card(
            onClick = { if (enabled) onClick() },
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f * alpha)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().alpha(alpha)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(description, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun initializeAds() {
        val configuration = RequestConfiguration.Builder().setTestDeviceIds(listOf("EMULATOR")).build()
        MobileAds.setRequestConfiguration(configuration)
        MobileAds.initialize(this)
    }

    private fun loadRewardedAd() {
        RewardedAd.load(this, TEST_REWARDED_AD_UNIT_ID, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) { rewardedAd = ad }
            override fun onAdFailedToLoad(error: LoadAdError) { rewardedAd = null }
        })
    }

    private fun showAdIfNecessary(onAction: () -> Unit) {
        if (viewModel.freeActionsRemaining > 0) {
            viewModel.freeActionsRemaining--
            onAction()
            return
        }
        val ad = rewardedAd ?: run { loadRewardedAd(); onAction(); return }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { rewardedAd = null; loadRewardedAd(); onAction() }
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) { rewardedAd = null; loadRewardedAd(); onAction() }
        }
        ad.show(this, OnUserEarnedRewardListener { })
    }

    @Composable
    private fun BannerAd(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val adWidth = (configuration.screenWidthDp - 32).coerceAtLeast(320)
        val adSize = remember(adWidth) { AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth) }
        val adView = remember { AdView(context).apply { adUnitId = TEST_BANNER_AD_UNIT_ID } }
        DisposableEffect(adView) { onDispose { adView.destroy() } }
        LaunchedEffect(adSize) { adView.setAdSize(adSize); adView.loadAd(AdRequest.Builder().build()) }
        AndroidView(modifier = modifier, factory = { adView })
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun clearPdfViewer() {
        if (fragmentContainerId == View.NO_ID) return
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) return
        val fragment = fragmentManager.findFragmentById(fragmentContainerId)
        if (fragment != null) {
            fragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
        }
    }

    private fun showPdf(file: File) {
        if (fragmentContainerId == View.NO_ID) return
        if (!file.exists() || file.length() == 0L) return

        val authority = "${packageName}.provider"
        val contentUri = FileProvider.getUriForFile(this, authority, file)
        
        lastGrantedUri?.let { revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        grantUriPermission(packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        lastGrantedUri = contentUri

        val pdfViewerFragment = PdfViewerFragment()
        pdfViewerFragment.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_START) {
                    try {
                        pdfViewerFragment.documentUri = contentUri
                        pdfViewerFragment.isToolboxVisible = false
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    pdfViewerFragment.lifecycle.removeObserver(this)
                }
            }
        })

        val fragmentManager = supportFragmentManager
        if (!fragmentManager.isStateSaved) {
            fragmentManager.beginTransaction()
                .replace(fragmentContainerId, pdfViewerFragment)
                .commitAllowingStateLoss()
        }
    }
}
