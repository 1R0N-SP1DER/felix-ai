package com.felix.faze

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Runs a command inside Termux without root, using Termux's own "run command" service. It needs three things from the person: Termux installed, the line
 * allow-external-apps=true in Termux's ~/.termux/termux.properties, and Faze allowed to "Run commands in Termux environment" in Android's app permissions.
 */
object TermuxRun {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val HOME = "/data/data/com.termux/files/home"
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    fun installed(context: Context): Boolean = try { context.packageManager.getPackageInfo(PACKAGE, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    fun allowed(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The intent that makes Termux run [script] in the background. Plain, so a test can read it. */
    fun intent(script: String): Intent = Intent("com.termux.RUN_COMMAND").setClassName(PACKAGE, "com.termux.app.RunCommandService")
        .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
        .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
        .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
        .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)

    /** Starts the script in Termux. Throws with what the person has to do when it cannot. */
    fun run(context: Context, script: String) {
        if (!installed(context)) throw IllegalStateException("Termux is not installed. Install Termux (from F-Droid) and build llama.cpp there.")
        if (!allowed(context)) throw IllegalStateException("Faze is not allowed to run commands in Termux. Android Settings > Apps > Faze > Permissions > Additional permissions > Run commands in Termux environment.")
        try {
            context.startService(intent(script))
        } catch (e: SecurityException) {
            throw IllegalStateException("Termux refused. In Termux run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties   then close and reopen Termux.")
        } catch (e: IllegalStateException) {
            throw IllegalStateException("Android would not let Faze start Termux from the background. Open Faze on screen and try again.")
        }
    }
}
