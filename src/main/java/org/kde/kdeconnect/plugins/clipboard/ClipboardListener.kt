/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.helpers.ThreadHelper.execute
import org.kde.kdeconnect_tp.BuildConfig
import top.miceworld.kdeconnectplus.shizuku.ClipContent
import top.miceworld.kdeconnectplus.shizuku.ShizukuClipboard
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipboardListener {
    enum class ClipboardContentType {
        Text,
        Password,
    }

    interface ClipboardObserver {
        fun clipboardChanged(content: String, contentType: ClipboardContentType)
    }

    private val observers: HashSet<ClipboardObserver> = HashSet()

    private val context: Context
    var currentContent: String? = null
        private set
    var currentContentType: ClipboardContentType = ClipboardContentType.Text
        private set
    var updateTimestamp: Long = 0
        private set

    private lateinit var cm: ClipboardManager

    private constructor(ctx: Context) {
        context = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            cm = context.getSystemService<ClipboardManager>()!!
            cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
        }
        // kdeconnect+：通过 Shizuku 在后台读剪贴板
        ShizukuClipboard.init(context) { content, sensitive ->
            publish(content, if (sensitive) ClipboardContentType.Password else ClipboardContentType.Text)
        }
        ShizukuClipboard.addStateListener { BackgroundService.instance?.updateForegroundNotification() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ClipboardPlugin.canSyncAutomatically(context)) {
            execute {
                try {
                    val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                    // Listen only ClipboardService errors after now
                    val logcatFilter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) { "E ClipboardService" } else { "ClipboardService:E" }
                    val process = Runtime.getRuntime().exec(arrayOf<String>("logcat", "-T", timeStamp, logcatFilter, "*:S"))
                    val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                    bufferedReader.forEachLine { line ->
                        if (line.contains(BuildConfig.APPLICATION_ID) && !ShizukuClipboard.isReady) {
                            context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    fun onClipboardChanged() {
        try {
            val clip = cm.primaryClip!!
            val item = clip.getItemAt(0)
            val content = item.coerceToText(context).toString()
            val contentType = detectContentType(clip)
            publish(content, contentType)
        } catch (_: Exception) {
            //Probably clipboard was not text
        }
    }

    private fun publish(content: String, contentType: ClipboardContentType) {
        if (content == currentContent && contentType == currentContentType) {
            return
        }
        updateTimestamp = System.currentTimeMillis()
        currentContent = content
        currentContentType = contentType

        for (observer in observers) {
            observer.clipboardChanged(content, contentType)
        }
    }

    fun setText(text: String?) {
        if (this::cm.isInitialized) {
            if (text == currentContent) {
                return
            }
            updateTimestamp = System.currentTimeMillis()
            currentContent = text
            currentContentType = ClipboardContentType.Text
            // kdeconnect+：优先经 Shizuku 以 shell 身份写入（ColorOS 等会拦截后台应用写剪贴板）
            if (text == null || !ShizukuClipboard.setText(text)) {
                cm.setPrimaryClip(ClipData.newPlainText(null, text).also(ClipContent::markFromRemote))
            }
        }
    }

    companion object {
        private var _instance: ClipboardListener? = null

        fun instance(context: Context): ClipboardListener {
            // FIXME: The _instance we return won't be completely initialized yet since initialization happens on a new thread (why?)
            return _instance ?: ClipboardListener(context).also { _instance = it }
        }

        fun detectContentType(clip: ClipData?): ClipboardContentType {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return ClipboardContentType.Text
            }
            if (clip?.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                return ClipboardContentType.Password
            }
            return ClipboardContentType.Text
        }
    }
}
