/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.bg

import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.MainThread
import com.crashlytics.android.Crashlytics
import com.github.shadowsocks.Core
import com.github.shadowsocks.utils.Commandline
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

class GuardedProcessPool : CoroutineScope {
    companion object {
        private const val TAG = "GuardedProcessPool"
        private val pid by lazy {
            Class.forName("java.lang.ProcessManager\$ProcessImpl").getDeclaredField("pid").apply { isAccessible = true }
        }
    }

    private inner class Guard(private val cmd: List<String>) {
        val abortChannel = Channel<Unit>()
        private val exitChannel = Channel<Int>()
        private val cmdName = File(cmd.first()).nameWithoutExtension
        private var startTime: Long = -1
        private lateinit var process: Process

        private fun streamLogger(input: InputStream, logger: (String, String) -> Int) = try {
            input.bufferedReader().forEachLine { logger(TAG, it) }
        } catch (_: IOException) { }    // ignore

        fun start() {
            startTime = SystemClock.elapsedRealtime()
            process = ProcessBuilder(cmd).directory(Core.deviceStorage.noBackupFilesDir).start()
        }

        suspend fun looper(onRestartCallback: (() -> Unit)?) {
            var running = true
            try {
                while (true) {
                    thread(name = "stderr-$cmdName") { streamLogger(process.errorStream, Log::e) }
                    thread(name = "stdout-$cmdName") {
                        runBlocking {
                            streamLogger(process.inputStream, Log::i)
                            exitChannel.send(process.waitFor()) // this thread also acts as a daemon thread for waitFor
                        }
                    }
                    if (select {
                                abortChannel.onReceive { true } // prefer abort to save work
                                exitChannel.onReceive { false }
                            }) break
                    running = false
                    if (SystemClock.elapsedRealtime() - startTime < 1000) {
                        Crashlytics.log(Log.WARN, TAG, "process exit too fast, stop guard: $cmdName")
                        break
                    }
                    Crashlytics.log(Log.DEBUG, TAG, "restart process: " + Commandline.toString(cmd))
                    start()
                    running = true
                    onRestartCallback?.invoke()
                }
            } finally {
                if (!running) return    // process already exited, nothing to be done
                if (Build.VERSION.SDK_INT < 24) {
                    val pid = pid.get(process) as Int
                    try {
                        Os.kill(pid, OsConstants.SIGTERM)
                    } catch (e: ErrnoException) {
                        if (e.errno != OsConstants.ESRCH) throw e
                    }
                    if (withTimeoutOrNull(500) { exitChannel.receive() } != null) return
                }
                process.destroy() // kill the process
                if (Build.VERSION.SDK_INT >= 26) {
                    if (withTimeoutOrNull(1000) { exitChannel.receive() } != null) return
                    process.destroyForcibly() // Force to kill the process if it's still alive
                }
                exitChannel.receive()
            }
        }
    }

    private val supervisor = SupervisorJob()
    override val coroutineContext get() = Dispatchers.Main + supervisor
    private val guards = ArrayList<Guard>()

    @MainThread
    suspend fun start(cmd: List<String>, onRestartCallback: (() -> Unit)? = null) {
        Crashlytics.log(Log.DEBUG, TAG, "start process: " + Commandline.toString(cmd))
        val guard = Guard(cmd)
        guard.start()
        guards += guard
        launch(start = CoroutineStart.UNDISPATCHED) { guard.looper(onRestartCallback) }
    }

    @MainThread
    suspend fun close() {
        guards.forEach { it.abortChannel.send(Unit) }
        supervisor.children.forEach { it.join() }
    }
}
