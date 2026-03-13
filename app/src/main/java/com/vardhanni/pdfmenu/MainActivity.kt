package com.vardhanni.pdfmenu

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.pdf.viewer.fragment.PdfViewerFragment
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vardhanni.pdfmenu.data.PdfRepositoryImpl
import com.vardhanni.pdfmenu.ui.PdfUiEvent
import com.vardhanni.pdfmenu.ui.PdfViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val viewModel: PdfViewModel by viewModels {
        PdfViewModel.Factory(PdfRepositoryImpl(contentResolver, cacheDir))
    }

    private var fragmentContainerId by mutableIntStateOf(View.NO_ID)

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            clearPdfViewer()
            viewModel.onFilePicked(it)
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvents.collect { event ->
                    when (event) {
                        is PdfUiEvent.ShowPdf -> showPdf(event.file)
                        is PdfUiEvent.ShowError -> Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG).show()
                        is PdfUiEvent.Toast -> Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        setContent {
            MaterialTheme {
                PdfMenuScreen(viewModel)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun PdfMenuScreen(viewModel: PdfViewModel) {
        var passwordInput by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("PDF Menu") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            },
            floatingActionButton = {
                Column(
                    modifier = Modifier.padding(start = 32.dp, bottom = 32.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    if (viewModel.isSaveVisible) {
                        SmallFloatingActionButton(
                            onClick = { viewModel.showCompressDialog = true },
                            modifier = Modifier.padding(bottom = 16.dp),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Icon(Icons.Default.Compress, "Compress")
                        }
                        SmallFloatingActionButton(
                            onClick = { savePdfLauncher.launch("unprotected_document.pdf") },
                            modifier = Modifier.padding(bottom = 16.dp),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(painterResource(android.R.drawable.ic_menu_save), "Save")
                        }
                    }
                    ExtendedFloatingActionButton(
                        onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
                        icon = { Icon(Icons.Default.Search, "Open") },
                        text = { Text("Open PDF") }
                    )
                }
            },
            floatingActionButtonPosition = FabPosition.Start
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        FragmentContainerView(context).apply {
                            val generatedId = View.generateViewId()
                            id = generatedId
                            fragmentContainerId = generatedId
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
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
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password"
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
                    }) {
                        Text("Open")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.showPasswordDialog = false
                        passwordInput = ""
                    }) {
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
                    Column {
                        Text("Quality: ${(viewModel.compressionQuality * 100).toInt()}%")
                        Slider(
                            value = viewModel.compressionQuality,
                            onValueChange = { viewModel.onCompressQualityChanged(it) },
                            valueRange = 0.1f..1.0f
                        )
                        Text("Estimated Size: ${formatFileSize(viewModel.compressedFileSize)}")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.applyCompression() }) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.showCompressDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
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
        val fragment = fragmentManager.findFragmentById(fragmentContainerId)
        if (fragment != null) {
            fragmentManager.beginTransaction().remove(fragment).commitNow()
        }
    }

    private fun showPdf(file: File) {
        if (fragmentContainerId == View.NO_ID) return
        
        val pdfViewerFragment = PdfViewerFragment()
        val contentUri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        
        supportFragmentManager.beginTransaction()
            .replace(fragmentContainerId, pdfViewerFragment)
            .runOnCommit {
                pdfViewerFragment.isToolboxVisible = false
                pdfViewerFragment.documentUri = contentUri
            }
            .commit()
    }
}