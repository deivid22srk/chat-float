package com.deivid22srk.chatfloat.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
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

/**
 * Foreground service that shows a floating chat overlay above other apps.
 *
 * Three modes:
 *   1. EXPANDED — full chat window (header + messages + input)
 *   2. MINIMIZED — small semi-transparent icon (drag to move, tap to expand)
 *   3. MINIMIZED + NEW MESSAGE — icon + a small popup bubble showing the
 *      last incoming message text, which auto-hides after a few seconds
 *
 * Keyboard fix: when the EditText gains focus, we toggle FLAG_NOT_FOCUSABLE
 * off so the soft keyboard can appear. When focus is lost, we set it back.
 */
class FloatingChatService : Service() {

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var expandedView: View? = null
    private var minimizedView: View? = null
    private var minimizedBubbleView: View? = null
    private var messagesTextView: TextView? = null
    private var messagesScrollView: ScrollView? = null
    private var inputEditText: EditText? = null
    private var expandedParams: WindowManager.LayoutParams? = null
    private var minimizedParams: WindowManager.LayoutParams? = null

    private var isMinimized = false
    private var lastSeenMessageId: Long = 0L
    private var lastIncomingMessage: ChatMessage? = null
    private var bubbleHideJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rootView == null) {
            showOverlay()
            startPolling()
        }
        return START_STICKY
    }

    // ============================================================
    // Expanded overlay (full chat window)
    // ============================================================

    private fun showOverlay() {
        val density = Resources.getSystem().displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // Root container that holds both expanded and minimized views.
        val root = FrameLayout(this)

        // --- Expanded view ---
        val expanded = buildExpandedView(dp)
        root.addView(expanded, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // --- Minimized view (hidden initially) ---
        val minimized = buildMinimizedView(dp)
        minimized.visibility = View.GONE
        root.addView(minimized, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))

        // --- New-message bubble (hidden initially) ---
        val bubble = buildMessageBubble(dp)
        bubble.visibility = View.GONE
        root.addView(bubble, FrameLayout.LayoutParams(
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
            width = dp(300)
        }

        windowManager.addView(root, params)
        rootView = root
        expandedView = expanded
        minimizedView = minimized
        minimizedBubbleView = bubble
        expandedParams = params
    }

    private fun buildExpandedView(dp: (Int) -> Int): View {
        val ctx = this

        val expanded = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#FDFDFF"))
                setStroke(dp(1), Color.parseColor("#E8E8F0"))
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            elevation = dp(8).toFloat()
        }

        // === Header (draggable) ===
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#5B5BF0"))
            }
        }
        val titleIcon = TextView(ctx).apply {
            text = "💬"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }
        val title = TextView(ctx).apply {
            text = "ChatFloat"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(4)
            layoutParams = lp
        }
        val btnMinimize = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            background = null
            setPadding(dp(10), dp(10), dp(10), dp(10))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        val btnClose = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setPadding(dp(10), dp(10), dp(10), dp(10))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        header.addView(titleIcon)
        header.addView(title)
        header.addView(btnMinimize)
        header.addView(btnClose)

        // === Messages area ===
        val messagesText = TextView(ctx).apply {
            setTextColor(Color.parseColor("#1F1F2E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            text = "Aguardando mensagens…"
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        val scrollView = ScrollView(ctx).apply {
            addView(messagesText)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200))
            lp.topMargin = dp(8)
            lp.bottomMargin = dp(8)
            layoutParams = lp
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F4F4F8"))
            }
        }

        // === Input row ===
        val input = EditText(ctx).apply {
            hint = "Digite uma mensagem…"
            setHintTextColor(Color.parseColor("#A0A0B0"))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E0E0EA"))
            }
            setTextColor(Color.parseColor("#1F1F2E"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 3
            isFocusable = true
            isFocusableInTouchMode = true
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(8)
            layoutParams = lp
        }
        val btnSend = Button(ctx).apply {
            text = "➤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#5B5BF0"))
            }
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            minEms = 1
        }
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input)
            addView(btnSend)
        }

        expanded.addView(header)
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

        // === Minimize ===
        btnMinimize.setOnClickListener { minimize() }

        // === Close ===
        btnClose.setOnClickListener { stopSelf() }

        // === Send ===
        btnSend.setOnClickListener {
            val text = input.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            input.text?.clear()
            input.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            // Restore FLAG_NOT_FOCUSABLE so the overlay doesn't steal touches
            setFocusable(false)
            scope.launch { GoBridge.sendMessage(text.trim()) }
        }

        // === Keyboard fix: toggle FLAG_NOT_FOCUSABLE on focus change ===
        input.setOnFocusChangeListener { _, hasFocus ->
            setFocusable(hasFocus)
            if (hasFocus) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        // Also show keyboard when tapped (some ROMs need explicit request)
        input.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        messagesTextView = messagesText
        messagesScrollView = scrollView
        inputEditText = input
        return expanded
    }

    /**
     * Toggles FLAG_NOT_FOCUSABLE on the overlay window so the EditText can
     * receive focus and the soft keyboard can appear.
     */
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
    // Minimized view (small semi-transparent icon)
    // ============================================================

    private fun buildMinimizedView(dp: (Int) -> Int): View {
        val ctx = this
        val icon = TextView(ctx).apply {
            text = "💬"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC5B5BF0")) // ~80% opacity
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            elevation = dp(6).toFloat()
        }
        // Layout params for the minimized icon (small, top-left)
        val iconParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.START)
        icon.layoutParams = iconParams

        // Drag + click handling
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
                        if (!moved) {
                            // Tap → expand
                            expand()
                        }
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
                setColor(Color.parseColor("#F2F2F8"))
                setStroke(dp(1), Color.parseColor("#E0E0EA"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(6).toFloat()
        }
        val sender = TextView(ctx).apply {
            text = ""
            setTextColor(Color.parseColor("#5B5BF0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
        }
        val body = TextView(ctx).apply {
            text = ""
            setTextColor(Color.parseColor("#1F1F2E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 3
        }
        bubble.addView(sender)
        bubble.addView(body)
        // Position the bubble next to the icon
        bubble.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        )
        bubble.tag = Pair(sender, body)
        return bubble
    }

    private fun minimize() {
        if (isMinimized) return
        isMinimized = true
        expandedView?.visibility = View.GONE
        minimizedView?.visibility = View.VISIBLE
        // Hide keyboard if visible
        inputEditText?.let { input ->
            input.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
        setFocusable(false)
        // Switch to a smaller window size for the icon
        val density = Resources.getSystem().displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        expandedParams?.let { p ->
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            rootView?.let { windowManager.updateViewLayout(it, p) }
        }
        minimizedParams = expandedParams
        // Mark current last message as seen so popup only triggers for
        // messages arriving AFTER the user minimized.
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
        minimizedBubbleView?.visibility = View.GONE
        expandedView?.visibility = View.VISIBLE
        val density = Resources.getSystem().displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        expandedParams?.let { p ->
            p.width = dp(300)
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            rootView?.let { windowManager.updateViewLayout(it, p) }
        }
        // Mark the current last message as seen so we don't re-popup old
        // messages next time we minimize. Fetch the latest from Go.
        bubbleHideJob?.cancel()
        minimizedBubbleView?.visibility = View.GONE
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
                    updateMessagesText(msgs)
                    checkForNewIncomingMessage(msgs)
                }
                delay(2000)
            }
        }
    }

    private fun updateMessagesText(msgs: List<ChatMessage>) {
        val textView = messagesTextView ?: return
        val scrollView = messagesScrollView ?: return

        if (msgs.isEmpty()) {
            textView.text = "Aguardando mensagens…"
            return
        }

        val sb = SpannableStringBuilder()
        msgs.takeLast(50).forEach { msg ->
            if (msg.isOutgoing) {
                val s = sb.length
                sb.append("Você")
                sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#5B5BF0")), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(AbsoluteSizeSpan(12, true), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n")
            } else {
                val s = sb.length
                sb.append(msg.senderName)
                sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#3A3AD0")), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(AbsoluteSizeSpan(12, true), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n")
            }
            val textStart = sb.length
            sb.append(msg.text)
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#1F1F2E")), textStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AbsoluteSizeSpan(13, true), textStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n\n")
        }
        textView.text = sb
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }

        // Only mark messages as "seen" when the window is EXPANDED (user is
        // actually looking at them). When minimized, we keep lastSeenMessageId
        // unchanged so that new incoming messages trigger the popup.
        if (!isMinimized && msgs.isNotEmpty()) {
            lastSeenMessageId = msgs.last().id
        }
    }

    /**
     * When minimized, shows a popup bubble with the latest incoming message.
     * The popup auto-hides after 4 seconds.
     *
     * IMPORTANT: this must run BEFORE updateMessagesText updates lastSeenMessageId
     * for the minimized case (handled above — lastSeenMessageId is only advanced
     * when expanded).
     */
    private fun checkForNewIncomingMessage(msgs: List<ChatMessage>) {
        if (!isMinimized) return
        if (msgs.isEmpty()) return
        // Find the latest INCOMING message (skip outgoing ones we sent)
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
        // New incoming message!
        lastSeenMessageId = newestIncoming.id
        lastIncomingMessage = newestIncoming
        showMessageBubble(newestIncoming)
    }

    private fun showMessageBubble(msg: ChatMessage) {
        val bubble = minimizedBubbleView ?: return
        val pair = bubble.tag as? Pair<TextView, TextView> ?: return
        pair.first.text = msg.senderName
        pair.second.text = msg.text
        bubble.visibility = View.VISIBLE

        // Position bubble to the right of the minimized icon
        minimizedParams?.let { p ->
            val bubbleParams = bubble.layoutParams as? FrameLayout.LayoutParams ?: return
            val density = Resources.getSystem().displayMetrics.density
            val dp = { v: Int -> (v * density).toInt() }
            // The FrameLayout gravity is set to TOP|START; we use leftMargin/topMargin
            // to offset the bubble to the right of the icon
            bubbleParams.leftMargin = (p.x ?: 0) + dp(64)
            bubbleParams.topMargin = (p.y ?: 0) + dp(8)
            bubbleParams.width = dp(200)
            bubble.layoutParams = bubbleParams
        }

        // Auto-hide after 4 seconds
        bubbleHideJob?.cancel()
        bubbleHideJob = scope.launch {
            delay(4000)
            bubble.visibility = View.GONE
        }
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
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        expandedView = null
        minimizedView = null
        minimizedBubbleView = null
        messagesTextView = null
        messagesScrollView = null
        inputEditText = null
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
