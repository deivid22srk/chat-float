package com.deivid22srk.chatfloat.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.deivid22srk.chatfloat.ui.AuthViewModel
import com.deivid22srk.chatfloat.ui.components.Avatar
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authVm: AuthViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by authVm.state.collectAsState()
    val authedState = state as? AuthViewModel.AuthState.Authenticated ?: return
    val isSaving by authVm.isSaving.collectAsState()
    val errorMsg by authVm.error.collectAsState()

    var usernameDraft by remember(authedState.username) {
        mutableStateOf(authedState.username)
    }
    // Local draft of the new avatar PNG bytes (null = no change / use existing URL)
    var pendingAvatarBytes by remember { mutableStateOf<ByteArray?>(null) }
    var removeAvatar by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // Load + downscale + encode as PNG bytes
            val bmp = loadAndScaleBitmap(context, uri, maxSize = 256)
            if (bmp != null) {
                pendingAvatarBytes = encodeBitmapToPng(bmp)
                removeAvatar = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            // === Avatar ===
            Text(
                text = "Foto de perfil",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { imagePicker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        // Preview the newly picked image
                        pendingAvatarBytes != null -> {
                            val bmp = BitmapFactory.decodeByteArray(
                                pendingAvatarBytes!!, 0, pendingAvatarBytes!!.size
                            )
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Avatar",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        // Show the existing avatar from URL (or nothing if removed)
                        !removeAvatar && authedState.avatarUrl != null -> {
                            Avatar(
                                url = authedState.avatarUrl,
                                base64 = null,
                                initials = authedState.username.take(2).uppercase(),
                                size = 96
                            )
                        }
                        else -> {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.size(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    TextButton(
                        onClick = { imagePicker.launch("image/*") }
                    ) {
                        Icon(
                            Icons.Filled.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Escolher foto")
                    }
                    if (pendingAvatarBytes != null || (!removeAvatar && authedState.avatarUrl != null)) {
                        TextButton(
                            onClick = {
                                pendingAvatarBytes = null
                                removeAvatar = true
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Remover foto")
                        }
                    }
                    Text(
                        text = "A imagem é redimensionada para 256×256, enviada para o " +
                            "Supabase Storage (bucket público) e a URL fica salva na " +
                            "sua conta. Outros usuários carregam a foto via essa URL.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // === Username ===
            Text(
                text = "Nome de usuário",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = usernameDraft,
                onValueChange = { usernameDraft = it.take(30) },
                label = { Text("Nome") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            val hasChanges = usernameDraft.isNotBlank() &&
                (usernameDraft != authedState.username ||
                    pendingAvatarBytes != null ||
                    (removeAvatar && authedState.avatarUrl != null))

            // Show error if any
            errorMsg?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }

            Button(
                onClick = {
                    if (usernameDraft.isNotBlank()) {
                        // The AuthViewModel sets isSaving=true during the async
                        // upload. We DON'T clear pendingAvatarBytes here — we
                        // wait for the LaunchedEffect below to detect when
                        // isSaving goes false AND the avatarUrl has been
                        // updated in authedState.
                        if (usernameDraft != authedState.username) {
                            authVm.updateUsername(usernameDraft.trim())
                        }
                        when {
                            pendingAvatarBytes != null -> {
                                authVm.updateAvatarBytes(pendingAvatarBytes)
                            }
                            removeAvatar -> {
                                authVm.updateAvatarBytes(null)
                            }
                        }
                    }
                },
                enabled = hasChanges && !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Enviando…")
                } else {
                    Text("Salvar alterações")
                }
            }

            // When isSaving transitions from true→false, the save completed.
            // Now we can safely clear the local pending state — the authedState
            // has been refreshed by the AuthViewModel and will show the new
            // avatarUrl (or empty if removed).
            var previousSaving by remember { mutableStateOf(false) }
            LaunchedEffect(isSaving) {
                if (previousSaving && !isSaving) {
                    // Save just finished — clear local pending state
                    pendingAvatarBytes = null
                    removeAvatar = false
                }
                previousSaving = isSaving
            }

            Spacer(Modifier.height(32.dp))

            // === Token info ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Seu token",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = authedState.token,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Use este token para recuperar sua conta em outro dispositivo " +
                            "ou após desinstalar o app.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // === Logout ===
            var showLogoutDialog by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Sair da conta", style = MaterialTheme.typography.labelLarge)
            }

            if (showLogoutDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showLogoutDialog = false },
                    title = { Text("Sair da conta?") },
                    text = {
                        Text(
                            "Você precisará do seu token de 8 caracteres para entrar novamente. " +
                                "Certifique-se de tê-lo anotado.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                showLogoutDialog = false
                                authVm.logout()
                            }
                        ) {
                            Text("Sair", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { showLogoutDialog = false }
                        ) {
                            Text("Cancelar")
                        }
                    }
                )
            }
        }
    }
}

// ============================================================
// Image helpers
// ============================================================

private fun loadAndScaleBitmap(context: android.content.Context, uri: Uri, maxSize: Int): Bitmap? {
    return runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, opts)
        input.close()

        var sampleSize = 1
        while (opts.outWidth / sampleSize > maxSize * 2 ||
            opts.outHeight / sampleSize > maxSize * 2
        ) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val input2 = context.contentResolver.openInputStream(uri) ?: return null
        val bmp = BitmapFactory.decodeStream(input2, null, decodeOpts)
        input2.close()

        bmp?.let { scaleCenterCrop(it, maxSize) }
    }.getOrNull()
}

private fun scaleCenterCrop(src: Bitmap, size: Int): Bitmap {
    val scale = maxOf(size.toFloat() / src.width, size.toFloat() / src.height)
    val scaledWidth = (src.width * scale).toInt()
    val scaledHeight = (src.height * scale).toInt()
    val scaled = Bitmap.createScaledBitmap(src, scaledWidth, scaledHeight, true)
    val x = (scaledWidth - size) / 2
    val y = (scaledHeight - size) / 2
    return Bitmap.createBitmap(scaled, x, y, size, size)
}

private fun encodeBitmapToPng(bmp: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    // Use JPEG for photos (much smaller than PNG for the same quality).
    // Quality 85 is visually indistinguishable from lossless for avatars.
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return out.toByteArray()
}
