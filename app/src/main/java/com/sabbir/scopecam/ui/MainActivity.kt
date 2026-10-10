package com.sabbir.scopecam.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabbir.scopecam.Config
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// Premium dark palette with gold accent (user preference: classy / heavy look)
private val BgDeep = Color(0xFF0B0E1A)
private val BgCard = Color(0xFF141927)
private val Gold = Color(0xFFD4AF37)
private val GoldDim = Color(0xFF8A7326)
private val TextMain = Color(0xFFF2EFE6)
private val TextDim = Color(0xFF9AA0B4)

class MainActivity : ComponentActivity() {

    private var pendingPickMode: String? = null

    // Compose state holders so the picker callback can update the UI
    private val videoPathState = mutableStateOf("")
    private val imagePathState = mutableStateOf("")

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val mode = pendingPickMode
            val destName = if (mode == "image") "source_image.jpg" else "source_video.mp4"
            val path = copyToScopeCamDir(this, uri, destName)
            if (path != null) {
                if (mode == "image") imagePathState.value = path else videoPathState.value = path
                Toast.makeText(this, "Selected: $destName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Copy failed", Toast.LENGTH_SHORT).show()
            }
        }
        pendingPickMode = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Preload existing config
        val cfg = Config.load()
        videoPathState.value = cfg.videoPath
        imagePathState.value = cfg.imagePath

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BgDeep,
                    surface = BgCard,
                    primary = Gold,
                    onBackground = TextMain,
                    onSurface = TextMain
                )
            ) {
                ScopeCamScreen(
                    videoPathState = videoPathState,
                    imagePathState = imagePathState,
                    onPickVideo = {
                        pendingPickMode = "video"
                        pickFile.launch("video/*")
                    },
                    onPickImage = {
                        pendingPickMode = "image"
                        pickFile.launch("image/*")
                    }
                )
            }
        }
    }

    companion object {
        /** Copies a content URI into /sdcard/DCIM/ScopeCam/ and returns the absolute path. */
        fun copyToScopeCamDir(context: Context, uri: Uri, destName: String): String? {
            return try {
                val dir = File("/sdcard/DCIM/ScopeCam")
                dir.mkdirs()
                val out = File(dir, destName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { fos ->
                        input.copyTo(fos)
                    }
                }
                out.absolutePath
            } catch (e: Exception) {
                null
            }
        }

        /** Writes the module config read by the Xposed hooks. */
        fun saveConfig(
            context: Context,
            enabled: Boolean,
            mode: String,
            videoPath: String,
            imagePath: String,
            streamUrl: String,
            blockOnFail: Boolean
        ): Boolean {
            return try {
                val dir = File("/sdcard/DCIM/ScopeCam")
                dir.mkdirs()
                val file = File(dir, "config.json")
                val json = JSONObject().apply {
                    put("enabled", enabled)
                    put("mode", mode)
                    put("video_path", videoPath)
                    put("image_path", imagePath)
                    put("stream_url", streamUrl)
                    put("block_on_fail", blockOnFail)
                }
                file.writeText(json.toString(2))
                Config.invalidate()
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeCamScreen(
    videoPathState: MutableState<String>,
    imagePathState: MutableState<String>,
    onPickVideo: () -> Unit,
    onPickImage: () -> Unit
) {
    val context = LocalContext.current
    val cfg = remember { Config.load() }

    var enabled by remember { mutableStateOf(cfg.enabled) }
    var mode by remember { mutableStateOf(cfg.mode.ifEmpty { "video" }) }
    var videoPath by videoPathState
    var imagePath by imagePathState
    var streamUrl by remember { mutableStateOf(cfg.streamUrl) }
    var blockOnFail by remember { mutableStateOf(cfg.blockOnFail) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0B0E1A), Color(0xFF11162A)))
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                "ScopeCam",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Gold
            )
            Text(
                "Virtual camera for LSPosed scope",
                fontSize = 14.sp,
                color = TextDim
            )

            // Master switch card
            Card(
                colors = CardDefaults.cardColors(containerColor = BgCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Enabled", color = TextMain, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Swap camera in scoped apps",
                            color = TextDim,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Gold)
                    )
                }
            }

            // Mode selector card
            Card(
                colors = CardDefaults.cardColors(containerColor = BgCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Video source", color = TextMain, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "video" to "Local video",
                        "image" to "Single image loop",
                        "stream" to "Network stream"
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { mode = value }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = mode == value,
                                onClick = { mode = value },
                                colors = RadioButtonDefaults.colors(selectedColor = Gold)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = TextMain)
                        }
                    }
                }
            }

            // Source pickers card
            Card(
                colors = CardDefaults.cardColors(containerColor = BgCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Source", color = TextMain, fontWeight = FontWeight.SemiBold)

                    if (mode == "video") {
                        OutlinedButton(
                            onClick = onPickVideo,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GoldDim)
                        ) {
                            Text(if (videoPath.isEmpty()) "Pick video file" else "Change video file")
                        }
                        if (videoPath.isNotEmpty()) {
                            Text(
                                File(videoPath).name,
                                color = TextDim,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (mode == "image") {
                        OutlinedButton(
                            onClick = onPickImage,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GoldDim)
                        ) {
                            Text(if (imagePath.isEmpty()) "Pick image file" else "Change image file")
                        }
                        if (imagePath.isNotEmpty()) {
                            Text(
                                File(imagePath).name,
                                color = TextDim,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (mode == "stream") {
                        OutlinedTextField(
                            value = streamUrl,
                            onValueChange = { streamUrl = it },
                            label = { Text("Stream URL (mp4)", color = TextDim) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = GoldDim,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain
                            ),
                            singleLine = true
                        )
                        Text(
                            "The stream is downloaded once, then looped like a local video.",
                            color = TextDim,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Failure mode card
            Card(
                colors = CardDefaults.cardColors(containerColor = BgCard),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Block camera on failure", color = TextMain, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Show black instead of the real camera when no source frame is ready",
                            color = TextDim,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = blockOnFail,
                        onCheckedChange = { blockOnFail = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Gold)
                    )
                }
            }

            // LSPosed scope info
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2138)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GoldDim, RoundedCornerShape(16.dp))
            ) {
                Text(
                    "Select target apps in LSPosed Manager → Modules → ScopeCam → Scope, then reboot.",
                    color = TextMain,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }

            // Save button
            Button(
                onClick = {
                    val ok = MainActivity.saveConfig(
                        context, enabled, mode, videoPath, imagePath,
                        streamUrl.trim(), blockOnFail
                    )
                    Toast.makeText(
                        context,
                        if (ok) "Saved! Reboot is recommended after scope changes."
                        else "Save failed - check storage permission.",
                        Toast.LENGTH_LONG
                    ).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Gold,
                    contentColor = Color(0xFF0B0E1A)
                )
            ) {
                Text("Save", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
