package com.deivid22srk.chatfloat.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.deivid22srk.chatfloat.ChatFloatApplication
import com.deivid22srk.chatfloat.MainActivity
import com.deivid22srk.chatfloat.R
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
 * Now backed by the Go library (libchatfloat.so) — all Supabase communication
 * happens in Go. This service just reads cached messages from Go every 2s
 * and renders them as a TextView.
 */
class FloatingChatService : Service() {

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var messagesTextView: TextView? = null
    private var messagesScrollView: ScrollView? = null

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

    private fun showOverlay() {
        val density = Resources.getSystem().displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F8F8FC"))
                setStroke(dp(1), Color.parseColor("#E0E0EA"))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // === Header ===
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val title = TextView(this).apply {
            text = "ChatFloat"
            setTextColor(Color.parseColor("#1F1F2E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(8)
            layoutParams = lp
        }
        val btnCollapse = ImageButton(this).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val btnClose = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        header.addView(title)
        header.addView(btnCollapse)
        header.addView(btnClose)

        // === Messages area ===
        val messagesText = TextView(this).apply {
            setTextColor(Color.parseColor("#1F1F2E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            text = "Aguardando mensagens…"
        }
        val scrollView = ScrollView(this).apply {
            addView(messagesText)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160))
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        // === Input row ===
        val input = EditText(this).apply {
            hint = "Mensagem…"
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E0E0EA"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 3
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(8)
            layoutParams = lp
        }
        val btnSend = Button(this).apply {
            text = "Enviar"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#5B5BF0"))
            }
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input)
            addView(btnSend)
        }

        root.addView(header)
        root.addView(scrollView)
        root.addView(inputRow)

        // === Layout params ===
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
            width = dp(280)
        }

        // === Dragging ===
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
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(root, params)
                }
            }
            true
        }

        // === Collapse toggle ===
        var collapsed = false
        btnCollapse.setOnClickListener {
            collapsed = !collapsed
            scrollView.visibility = if (collapsed) View.GONE else View.VISIBLE
            inputRow.visibility = if (collapsed) View.GONE else View.VISIBLE
            btnCollapse.setImageResource(
                if (collapsed) android.R.drawable.arrow_up_float
                else android.R.drawable.arrow_down_float
            )
        }

        // === Close ===
        btnClose.setOnClickListener {
            stopSelf()
        }

        // === Send ===
        btnSend.setOnClickListener {
            val text = input.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            input.text?.clear()
            input.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)

            scope.launch {
                GoBridge.sendMessage(text.trim())
            }
        }

        // === Add the view ===
        windowManager.addView(root, params)
        rootView = root
        messagesTextView = messagesText
        messagesScrollView = scrollView
    }

    private fun startPolling() {
        if (pollingJob != null) return
        pollingJob = scope.launch {
            while (true) {
                runCatching {
                    val msgs = GoBridge.getMessages()
                    updateMessagesText(msgs)
                }
                delay(2000)
            }
        }
    }

    private fun updateMessagesText(msgs: List<com.deivid22srk.chatfloat.data.ChatMessage>) {
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
                sb.append("Você: ")
                sb.setSpan(
                    StyleSpan(android.graphics.Typeface.BOLD),
                    s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                val s = sb.length
                sb.append("${msg.senderName}: ")
                sb.setSpan(
                    StyleSpan(android.graphics.Typeface.BOLD),
                    s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            sb.append(msg.text)
            sb.append("\n\n")
        }
        textView.text = sb
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
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
        rootView?.let {
            runCatching { windowManager.removeView(it) }
        }
        rootView = null
        messagesTextView = null
        messagesScrollView = null
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
