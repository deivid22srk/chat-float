package com.deivid22srk.chatfloat.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.deivid22srk.chatfloat.ChatFloatApplication
import com.deivid22srk.chatfloat.MainActivity
import com.deivid22srk.chatfloat.R
import com.deivid22srk.chatfloat.data.ChatMessage
import com.deivid22srk.chatfloat.data.GoBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that shows a floating chat overlay above other apps.
 *
 * Three modes:
 *   1. EXPANDED — full chat window with bubble-style messages + action panel
 *   2. MINIMIZED — small semi-transparent icon (drag to move, tap to expand)
 *   3. MINIMIZED + NEW MESSAGE — icon + popup bubble with latest incoming msg
 *
 * The overlay uses native Android Views (not Compose) for performance, but
 * mirrors the same visual style as the Compose app:
 *   - Indigo primary (#6366F1)
 *   - Bubble outgoing: indigo bg + white text
 *   - Bubble incoming: neutral-100 bg + dark text
 *   - Rounded corners, avatars loaded via Coil from Supabase Storage URLs
 *
 * Action panel buttons (when expanded):
 *   - Screenshot (📸): captures screen and sends "📸 Screenshot" message
 *   - Copy last (📋): copies last incoming message to clipboard
 *   - Clear (🗑): clears the local message display (not the server)
 *   - Minimize (─): collapses to icon
 *   - Close (✕): stops the service
 */
class FloatingChatService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var imageLoader: ImageLoader
    private var rootView: View? = null
    private var expandedView: View? = null
    private var minimizedView: View? = null
    private var messagesContainer: LinearLayout? = null
    private var messagesScrollView: ScrollView? = null
    private var inputEditText: EditText? = null
    private var expandedParams: WindowManager.LayoutParams? = null
    private var minimizedParams: WindowManager.LayoutParams? = null

    // Bubble popup (separate window)
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleSenderText: TextView? = null
    private var bubbleBodyText: TextView? = null

    private var isMinimized = false
    private var lastSeenMessageId: Long = 0L
    private var lastIncomingMessage: ChatMessage? = null
    private var bubbleHideJob: Job? = null
    private var cachedMessages: List<ChatMessage> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollingJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Colors — resolved in onCreate based on system dark/light theme.
    // Light palette mirrors the Compose LightColors; dark palette mirrors DarkColors.
    private var colorPrimary = Color.parseColor("#6366F1")
    private var colorPrimaryDark = Color.parseColor("#4F46E5")
    private var colorSurface = Color.parseColor("#FAFAFC")
    private var colorSurfaceElevated = Color.parseColor("#FFFFFF")
    private var colorSurfaceVariant = Color.parseColor("#F1F1F5")
    private var colorOutline = Color.parseColor("#E4E4E7")
    private var colorTextPrimary = Color.parseColor("#18181B")
    private var colorTextSecondary = Color.parseColor("#71717A")
    private var colorBubbleOutgoing = Color.parseColor("#6366F1")
    private var colorBubbleIncoming = Color.parseColor("#F4F4F5")
    private var colorAvatarBg = Color.parseColor("#E0E7FF")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        imageLoader = ImageLoader.Builder(this).build()
        resolveThemeColors()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Sets the color palette based on the system's current dark/light mode.
     * The overlay re-reads this every time it's created, so if the user
     * toggles dark mode while the service is running, the new colors apply
     * on next overlay rebuild.
     */
    private fun resolveThemeColors() {
        val nightMode = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (isDark) {
            colorPrimary = Color.parseColor("#818CF8")        // indigo-400
            colorPrimaryDark = Color.parseColor("#4F46E5")
            colorSurface = Color.parseColor("#0F0F14")
            colorSurfaceElevated = Color.parseColor("#1A1A22")
            colorSurfaceVariant = Color.parseColor("#27272E")
            colorOutline = Color.parseColor("#3F3F46")
            colorTextPrimary = Color.parseColor("#FAFAFC")
            colorTextSecondary = Color.parseColor("#A1A1AA")
            colorBubbleOutgoing = Color.parseColor("#6366F1")
            colorBubbleIncoming = Color.parseColor("#27272E")
            colorAvatarBg = Color.parseColor("#3F3F46")
        } else {
            colorPrimary = Color.parseColor("#6366F1")
            colorPrimaryDark = Color.parseColor("#4F46E5")
            colorSurface = Color.parseColor("#FAFAFC")
            colorSurfaceElevated = Color.parseColor("#FFFFFF")
            colorSurfaceVariant = Color.parseColor("#F1F1F5")
            colorOutline = Color.parseColor("#E4E4E7")
            colorTextPrimary = Color.parseColor("#18181B")
            colorTextSecondary = Color.parseColor("#71717A")
            colorBubbleOutgoing = Color.parseColor("#6366F1")
            colorBubbleIncoming = Color.parseColor("#F4F4F5")
            colorAvatarBg = Color.parseColor("#E0E7FF")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rootView == null) {
            showOverlay()
            startPolling()
        }
        return START_STICKY
    }

    // ============================================================
    // Expanded overlay (full chat window with bubbles + action panel)
    // ============================================================

    private fun showOverlay() {
        val density = Resources.getSystem().displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = FrameLayout(this)

        val expanded = buildExpandedView(dp)
        root.addView(expanded, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        val minimized = buildMinimizedView(dp)
        minimized.visibility = View.GONE
        root.addView(minimized, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
            width = dp(280)
        }

        windowManager.addView(root, params)
        rootView = root
        expandedView = expanded
        minimizedView = minimized
        expandedParams = params
    }

    private fun buildExpandedView(dp: (Int) -> Int): View {
        val ctx = this

        val expanded = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(colorSurfaceElevated)
                setStroke(dp(1), colorOutline)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(10).toFloat()
        }

        // === Header (draggable) ===
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(6), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(colorPrimary)
            }
        }
        val titleEmoji = TextView(ctx).apply {
            text = "💬"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val title = TextView(ctx).apply {
            text = "ChatFloat"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(8)
            layoutParams = lp
        }
        val btnMinimize = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        val btnClose = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        header.addView(titleEmoji)
        header.addView(title)
        header.addView(btnMinimize)
        header.addView(btnClose)

        // === Action panel (horizontal scroll of quick action buttons) ===
        val actionPanel = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        }
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }

        val btnScreenshot = makeActionButton(dp, "📸", "Print")
        val btnCopy = makeActionButton(dp, "📋", "Copiar")
        val btnClear = makeActionButton(dp, "🗑", "Limpar")
        val btnScroll = makeActionButton(dp, "⬇", "Rolar")
        actionRow.addView(btnScreenshot)
        actionRow.addView(btnCopy)
        actionRow.addView(btnClear)
        actionRow.addView(btnScroll)
        actionPanel.addView(actionRow)

        // === Messages area (bubble-style) ===
        val messagesContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scrollView = ScrollView(ctx).apply {
            addView(messagesContainer)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160))
            lp.topMargin = dp(8)
            lp.bottomMargin = dp(8)
            layoutParams = lp
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(colorSurfaceVariant)
            }
        }
        val emptyHint = TextView(ctx).apply {
            text = "Aguardando mensagens…"
            setTextColor(colorTextSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(60), 0, 0)
            tag = "empty_hint"
        }
        messagesContainer.addView(emptyHint)

        // === Input row ===
        val input = EditText(ctx).apply {
            hint = "Mensagem…"
            setHintTextColor(colorTextSecondary)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(colorSurface)
                setStroke(dp(1), colorOutline)
            }
            setTextColor(colorTextPrimary)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            isFocusable = true
            isFocusableInTouchMode = true
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(6)
            layoutParams = lp
        }
        val btnSend = Button(ctx).apply {
            text = "➤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(colorPrimary)
            }
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minEms = 1
        }
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input)
            addView(btnSend)
        }

        expanded.addView(header)
        expanded.addView(actionPanel)
        expanded.addView(scrollView)
        expanded.addView(inputRow)

        // === Dragging via header ===
        expandedParams?.let { params ->
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            header.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        rootView?.let { windowManager.updateViewLayout(it, params) }
                    }
                }
                true
            }
        }

        // === Button handlers ===
        btnMinimize.setOnClickListener { minimize() }
        btnClose.setOnClickListener { stopSelf() }

        btnSend.setOnClickListener {
            val text = input.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            input.text?.clear()
            input.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            setFocusable(false)
            scope.launch { GoBridge.sendMessage(text.trim()) }
        }

        btnScreenshot.setOnClickListener { handleScreenshot() }
        btnCopy.setOnClickListener { handleCopyLastMessage() }
        btnClear.setOnClickListener { handleClearDisplay() }
        btnScroll.setOnClickListener {
            messagesScrollView?.post { messagesScrollView?.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        // === Keyboard fix ===
        input.setOnFocusChangeListener { _, hasFocus ->
            setFocusable(hasFocus)
            if (hasFocus) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        input.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        messagesContainer?.let { this.messagesContainer = it }
        this.messagesScrollView = scrollView
        this.inputEditText = input
        return expanded
    }

    private fun makeActionButton(dp: (Int) -> Int, emoji: String, label: String): View {
        val ctx = this
        val btn = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(colorSurfaceVariant)
                setStroke(dp(1), colorOutline)
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            layoutParams = lp
            isClickable = true
        }
        val emojiTv = TextView(ctx).apply {
            text = emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val labelTv = TextView(ctx).apply {
            text = label
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.NORMAL)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(4)
            layoutParams = lp
        }
        btn.addView(emojiTv)
        btn.addView(labelTv)
        return btn
    }

    private fun setFocusable(focusable: Boolean) {
        val params = expandedParams ?: return
        val root = rootView ?: return
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    // ============================================================
    // Message rendering (bubble-style, mirrors Compose MessageBubble)
    // ============================================================

    private fun updateMessagesDisplay(msgs: List<ChatMessage>) {
        val container = messagesContainer ?: return
        val scrollView = messagesScrollView ?: return
        cachedMessages = msgs

        // Remove all views except the empty hint
        container.removeAllViews()
        if (msgs.isEmpty()) {
            val emptyHint = TextView(this).apply {
                text = "Aguardando mensagens…"
                setTextColor(colorTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(60), 0, 0)
            }
            container.addView(emptyHint)
            return
        }

        val density = Resources.getSystem().displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        msgs.takeLast(30).forEach { msg ->
            val isOutgoing = msg.isOutgoing
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (isOutgoing) Gravity.END else Gravity.START
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(2)
                lp.bottomMargin = dp(2)
                layoutParams = lp
            }

            // Avatar (only for incoming)
            if (!isOutgoing) {
                val avatar = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colorAvatarBg)
                    }
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                }
                // Load avatar from URL via Coil, or leave empty for initials overlay
                val url = msg.senderAvatar
                if (url != null && url.isNotEmpty()) {
                    val request = ImageRequest.Builder(this)
                        .data(url)
                        .target(avatar)
                        .build()
                    imageLoader.enqueue(request)
                }
                // Frame containing avatar + initials overlay
                val avatarFrame = FrameLayout(this).apply {
                    val lp = LinearLayout.LayoutParams(dp(24), dp(24))
                    lp.marginEnd = dp(5)
                    lp.gravity = Gravity.TOP or Gravity.START
                    layoutParams = lp
                }
                avatar.layoutParams = FrameLayout.LayoutParams(dp(24), dp(24))
                val initials = TextView(this).apply {
                    text = msg.senderName.take(2).uppercase()
                    setTextColor(colorPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(dp(24), dp(24))
                }
                avatarFrame.addView(avatar)
                avatarFrame.addView(initials)
                row.addView(avatarFrame)
            }

            // Bubble column (sender name + bubble body)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (isOutgoing) Gravity.END else Gravity.START
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            }

            if (!isOutgoing) {
                val sender = TextView(this).apply {
                    text = msg.senderName
                    setTextColor(colorPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.marginStart = dp(10)
                    lp.bottomMargin = dp(2)
                    layoutParams = lp
                }
                col.addView(sender)
            }

            val bubble = TextView(this).apply {
                text = msg.text
                setTextColor(if (isOutgoing) Color.WHITE else colorTextPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    cornerRadii = floatArrayOf(
                        dp(14).toFloat(), dp(14).toFloat(),
                        dp(14).toFloat(), dp(14).toFloat(),
                        if (isOutgoing) dp(4).toFloat() else dp(14).toFloat(),
                        if (isOutgoing) dp(4).toFloat() else dp(14).toFloat(),
                        if (isOutgoing) dp(14).toFloat() else dp(4).toFloat(),
                        if (isOutgoing) dp(14).toFloat() else dp(4).toFloat()
                    )
                    setColor(if (isOutgoing) colorBubbleOutgoing else colorBubbleIncoming)
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(1)
                maxWidth = dp(190)
                layoutParams = lp
            }
            col.addView(bubble)

            // Timestamp
            val time = TextView(this).apply {
                text = formatTime(msg.timestamp)
                setTextColor(colorTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.gravity = if (isOutgoing) Gravity.END else Gravity.START
                if (isOutgoing) lp.marginEnd = dp(4) else lp.marginStart = dp(8)
                layoutParams = lp
            }
            col.addView(time)

            row.addView(col)
            container.addView(row)
        }

        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }

        if (msgs.isNotEmpty()) {
            lastSeenMessageId = if (!isMinimized) msgs.last().id else lastSeenMessageId
        }
    }

    private fun formatTime(timestampMs: Long): String {
        if (timestampMs <= 0) return ""
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestampMs))
    }

    private fun dp(v: Int): Int {
        val density = Resources.getSystem().displayMetrics.density
        return (v * density).toInt()
    }

    // ============================================================
    // Action panel handlers
    // ============================================================

    private fun handleScreenshot() {
        if (com.deivid22srk.chatfloat.service.ScreenshotAccessibilityService.isEnabled()) {
            // Accessibility service is running — take the screenshot
            Toast.makeText(this, "Capturando tela…", Toast.LENGTH_SHORT).show()
            val ok = com.deivid22srk.chatfloat.service.ScreenshotAccessibilityService.requestScreenshot()
            if (!ok) {
                Toast.makeText(this, "Falha ao capturar. Tente novamente.", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Need to enable the accessibility service first
            Toast.makeText(
                this,
                "Ative o ChatFloat em Acessibilidade para usar o Print",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }
    }

    private fun handleCopyLastMessage() {
        val last = cachedMessages.lastOrNull { !it.isOutgoing } ?: run {
            Toast.makeText(this, "Nenhuma mensagem para copiar", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ChatFloat", last.text))
        Toast.makeText(this, "Mensagem copiada!", Toast.LENGTH_SHORT).show()
    }

    private fun handleClearDisplay() {
        // Just clear the visual display; messages stay on the server
        messagesContainer?.let { container ->
            container.removeAllViews()
            val emptyHint = TextView(this).apply {
                text = "Display limpo (mensagens continuam no servidor)"
                setTextColor(colorTextSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                setPadding(0, dp(60), 0, 0)
            }
            container.addView(emptyHint)
        }
        Toast.makeText(this, "Display limpo", Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // Minimized view
    // ============================================================

    private fun buildMinimizedView(dp: (Int) -> Int): View {
        val ctx = this
        val icon = TextView(ctx).apply {
            text = "💬"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC6366F1"))
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            elevation = dp(8).toFloat()
        }
        val iconParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.START)
        icon.layoutParams = iconParams

        icon.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = minimizedParams?.x ?: 0
                        initialY = minimizedParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (dx * dx + dy * dy > 25) moved = true
                        minimizedParams?.let { p ->
                            p.x = initialX + dx.toInt()
                            p.y = initialY + dy.toInt()
                            rootView?.let { windowManager.updateViewLayout(it, p) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) expand()
                    }
                }
                return true
            }
        })
        return icon
    }

    private fun buildMessageBubble(dp: (Int) -> Int): View {
        val ctx = this
        val bubble = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(colorSurfaceElevated)
                setStroke(dp(1), colorOutline)
            }
            setPadding(dp(14), dp(10), dp(14), dp(12))
            elevation = dp(8).toFloat()
        }
        val sender = TextView(ctx).apply {
            text = ""
            setTextColor(colorPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
        }
        val body = TextView(ctx).apply {
            text = ""
            setTextColor(colorTextPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 3
            setPadding(0, dp(2), 0, 0)
        }
        bubble.addView(sender)
        bubble.addView(body)
        bubbleSenderText = sender
        bubbleBodyText = body
        return bubble
    }

    private fun minimize() {
        if (isMinimized) return
        isMinimized = true
        expandedView?.visibility = View.GONE
        minimizedView?.visibility = View.VISIBLE
        inputEditText?.let { input ->
            input.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
        setFocusable(false)
        expandedParams?.let { p ->
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            rootView?.let { windowManager.updateViewLayout(it, p) }
        }
        minimizedParams = expandedParams
        scope.launch {
            runCatching {
                val msgs = GoBridge.getMessages()
                if (msgs.isNotEmpty()) {
                    lastSeenMessageId = msgs.last().id
                }
            }
        }
    }

    private fun expand() {
        if (!isMinimized) return
        isMinimized = false
        minimizedView?.visibility = View.GONE
        expandedView?.visibility = View.VISIBLE
        val density = Resources.getSystem().displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        expandedParams?.let { p ->
            p.width = dp(280)
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            rootView?.let { windowManager.updateViewLayout(it, p) }
        }
        bubbleHideJob?.cancel()
        hideMessageBubble()
        scope.launch {
            runCatching {
                val msgs = GoBridge.getMessages()
                if (msgs.isNotEmpty()) {
                    lastSeenMessageId = msgs.last().id
                }
            }
        }
    }

    // ============================================================
    // Polling + new-message detection
    // ============================================================

    private fun startPolling() {
        if (pollingJob != null) return
        pollingJob = scope.launch {
            while (true) {
                runCatching {
                    val msgs = GoBridge.getMessages()
                    withContext(Dispatchers.Main) {
                        updateMessagesDisplay(msgs)
                        checkForNewIncomingMessage(msgs)
                    }
                }
                delay(2000)
            }
        }
    }

    private fun checkForNewIncomingMessage(msgs: List<ChatMessage>) {
        if (!isMinimized) return
        if (msgs.isEmpty()) return
        var newestIncoming: ChatMessage? = null
        for (m in msgs) {
            if (!m.isOutgoing) {
                if (newestIncoming == null || m.id > newestIncoming.id) {
                    newestIncoming = m
                }
            }
        }
        if (newestIncoming == null) return
        if (newestIncoming.id <= lastSeenMessageId) return
        lastSeenMessageId = newestIncoming.id
        lastIncomingMessage = newestIncoming
        showMessageBubble(newestIncoming)
    }

    private fun showMessageBubble(msg: ChatMessage) {
        val density = Resources.getSystem().displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        bubbleSenderText?.text = msg.senderName
        bubbleBodyText?.text = msg.text

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val iconX = minimizedParams?.x ?: 40
        val iconY = minimizedParams?.y ?: 200
        val iconSize = dp(56)
        val bubbleWidth = dp(220)
        val displayWidth = Resources.getSystem().displayMetrics.widthPixels
        val placeOnRight = iconX + iconSize + bubbleWidth + dp(16) < displayWidth
        val bubbleX = if (placeOnRight) iconX + iconSize + dp(8) else iconX - bubbleWidth - dp(8)
        val bubbleY = iconY

        if (bubbleView == null) {
            val bubble = buildMessageBubble(dp)
            val params = WindowManager.LayoutParams(
                bubbleWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bubbleX
                y = bubbleY
            }
            windowManager.addView(bubble, params)
            bubbleView = bubble
            bubbleParams = params
        } else {
            bubbleParams?.let { p ->
                p.x = bubbleX
                p.y = bubbleY
                p.width = bubbleWidth
                runCatching { windowManager.updateViewLayout(bubbleView, p) }
            }
        }
        bubbleView?.visibility = View.VISIBLE

        bubbleHideJob?.cancel()
        bubbleHideJob = scope.launch {
            delay(4000)
            hideMessageBubble()
        }
    }

    private fun hideMessageBubble() {
        bubbleView?.visibility = View.GONE
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ChatFloatApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_chat))
            .setContentText("Chat flutuante ativo")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        pollingJob = null
        bubbleHideJob?.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        expandedView = null
        minimizedView = null
        messagesContainer = null
        messagesScrollView = null
        inputEditText = null
        bubbleSenderText = null
        bubbleBodyText = null
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
