/*
 * Copyright 2016 dbgsprw / dbgsprw@gmail.com
 * Copyright 2016 Young Ho Cha / ganadist@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package dbgsprw.core

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.util.io.SafeFileOutputStream
import dbgsprw.app.BuildConsole
import dbgsprw.app.BuildService
import dbgsprw.app.getAndroidModule
import dbgsprw.device.Device
import java.io.BufferedOutputStream
import java.io.File
import java.util.regex.Pattern

/**
 * Created by ganadist on 16. 3. 1.
 */
class BuildServiceImpl(val mProject: Project) : CommandExecutor(), BuildService {
    private val LOG = Logger.getInstance(BuildServiceImpl::class.java)
    private var mTargetProduct = ""
    private var mBuildVariant = ""
    private var mOutDir = ""
    private var mTarget = ""
    private var mTargetSdk = false
    private var mOneShotMakefile: String? = null
    private val CM_PRODUCT_PREFIX = "cm_"

    private var mComboProcess: Process? = null
    private var mLunchProcess: Process? = null
    private var mBuildProcess: Process? = null
    private var mSyncProcess: Process? = null
    private val WHITESPACE = Pattern.compile("\\s+")

    init {
        setenv("USE_CCACHE", "1")
        directory(mProject.basePath!!)
    }

    override fun dispose() {
        val processes = arrayOf(mComboProcess, mLunchProcess, mBuildProcess, mSyncProcess)
        processes.forEach { p -> p?.destroy() }
    }

    override fun setProduct(product: String, variant: String) {
        mTargetProduct = product
        mBuildVariant = variant
        updateOutDir()
    }

    override fun setTarget(target: String) {
        mTarget = target
        mOneShotMakefile = null
        mTargetSdk = mTarget.contains("sdk")
        updateOutDir()
    }

    override fun setOneShotDirectory(directory: String) {
        mTarget = "all_modules"
        mOneShotMakefile = directory + File.separator + Utils.ANDROID_MK
    }

    override fun setOutPathListener(listener: BuildService.OutPathListener?) {
        if (listener == null) {
            mOutPathListener = mDummyOutPathListener
        } else {
            mOutPathListener = listener
        }
    }

    override fun runCombo(listener: BuildService.ComboMenuListener) {
        val command = listOf("source ${Utils.ENVSETUP_SH} > /dev/null",
                "printf '%s\n' \${LUNCH_MENU_CHOICES[@]} | cut -f 1 -d - | sort -u")

        mComboProcess = run(command, object : CommandHandler {
            override fun onOut(line: String) {
                listener.onTargetAdded(line)
            }

            override fun onExit(code: Int) {
                listener.onCompleted()
                mComboProcess = null
            }
        }, true)
    }

    private fun getCmBuild(): String {
        if (mTargetProduct.startsWith(CM_PRODUCT_PREFIX)) {
            return mTargetProduct.substring(CM_PRODUCT_PREFIX.length)
        }
        return ""
    }

    override fun build(jobs: Int, verbose: Boolean, extras: String, listener: BuildConsole.ExitListener) {
        updateAndroidJavaHome()

        val command: MutableList<String> = mutableListOf()
        command.add("make")
        if (jobs > 1) {
            command.add("-j$jobs")
        }
        command.add("TARGET_PRODUCT=$mTargetProduct")

        val cmBuild = getCmBuild()
        if (cmBuild.isNotEmpty()) {
            command.add("CM_BUILD=$cmBuild")
            command.add("BUILD_WITH_COLORS=0") // turn off color
            command.add("CLANG_CONFIG_EXTRA_CFLAGS=-fno-color-diagnostics")
            command.add("CLANG_CONFIG_EXTRA_CPPFLAGS=-fno-color-diagnostics")
        }

        command.add("TARGET_BUILD_VARIANT=$mBuildVariant");
        if (!mOneShotMakefile.isNullOrEmpty()) {
            command.add("ONE_SHOT_MAKEFILE=$mOneShotMakefile")
        }
        command.add(mTarget)
        if (verbose) {
            command.add("showcommands")
        }
        if (!extras.isNullOrBlank()) {
            command.addAll(extras.split(WHITESPACE))
        }

        mBuildProcess = run(command, getConsole().run(
                ExitListenerWrapper(listener, Runnable { mBuildProcess = null })))
    }

    override fun stopBuild() {
        mBuildProcess?.destroy()
        mBuildProcess = null
    }

    override fun sync(device: Device, partition: String, filename: String, wipe: Boolean, listener: BuildConsole.ExitListener) {
        val command = device.write(partition, filename, wipe)

        mSyncProcess = run(command, getConsole().run(
                ExitListenerWrapper(listener, Runnable { mSyncProcess = null })),
                shell = true)
    }

    override fun stopSync() {
        mSyncProcess?.destroy()
        mSyncProcess = null
    }

    override fun canBuild(): Boolean {
        return (mBuildProcess == null) && (mSyncProcess == null) && !mOutDir.isNullOrBlank()
    }

    override fun canSync(): Boolean {
        return (mBuildProcess == null) && (mSyncProcess == null)
    }

    private class ExitListenerWrapper(val mListener: BuildConsole.ExitListener,
                                      val action: Runnable) :
            BuildConsole.ExitListener by mListener {
        override fun onExit() {
            action.run()
            mListener.onExit()
        }
    }

    private fun getConsole(): BuildConsole {
        return ServiceManager.getService(mProject, BuildConsole::class.java)!!
    }

    private fun updateAndroidJavaHome() {
        val module = mProject.getAndroidModule()
        if (module == null) {
            return
        }
        val moduleSdk = ModuleRootManager.getInstance(module).sdk

        if (moduleSdk == null) {
            return;
        }

        val home = moduleSdk.homePath!!

        setenv("ANDROID_JAVA_HOME", home)
        val path = getenv("PATH")
        val jdkBinPath = arrayOf(home, "bin").joinToString(File.separator)
        if (!path.startsWith(jdkBinPath)) {
            setenv("PATH", jdkBinPath + File.pathSeparator + path)
        }
    }

    private fun updateOutDir() {
        if (mTargetProduct.isNullOrBlank() || mBuildVariant.isNullOrBlank()) {
            return
        }
        var outDir = arrayOf("out", mTargetProduct, mBuildVariant).joinToString("-")
        if (mTargetSdk) {
            outDir = arrayOf(outDir, "sdk").joinToString("-")
        }
        if (outDir != mOutDir) {
            mOutPathListener.onOutDirChanged(outDir)
            mOutDir = outDir
            setenv("OUT_DIR", outDir)
            generateBuildSpec()
            mLunchProcess?.destroy()
            mLunchProcess = findProductOutPath()
        }
    }


    private fun findProductOutPath(): Process {
        val selectedTarget = mTargetProduct + '-' + mBuildVariant
        LOG.info("target = $selectedTarget out_dir = $mOutDir")
        val command = listOf("source ${Utils.ENVSETUP_SH} > /dev/null",
                "lunch $selectedTarget > /dev/null",
                "echo \$ANDROID_PRODUCT_OUT")

        return run(command, object : CommandHandler {
            override fun onOut(line: String) {
                var path = line
                LOG.info("ANDROID_PRODUCT_OUT set " + path)
                setenv("ANDROID_PRODUCT_OUT", path);
                if (!path.startsWith(File.separator)) {
                    path = directory() + File.separator + path
                }
                mOutPathListener.onAndroidProductOutChanged(path)
            }
        }, true)
    }

    private val mDummyOutPathListener = object : BuildService.OutPathListener {}
    private var mOutPathListener: BuildService.OutPathListener = mDummyOutPathListener

    private fun generateBuildSpec() {
        val FIRST_LINE = "# generated from AndroidBuilder\n"
        val sb = StringBuilder(FIRST_LINE)
        sb.append("TARGET_PRODUCT?=$mTargetProduct\n")
        sb.append("TARGET_BUILD_VARIANT?=$mBuildVariant\n")
        sb.append("OUT_DIR?=out-$(TARGET_PRODUCT)-$(TARGET_BUILD_VARIANT)\n")
        val cmBuild = getCmBuild()
        if (cmBuild.isNotEmpty()) {
            sb.append("CM_BUILD?=$cmBuild\n")
        }

        val specFileName = Utils.BUILDSPEC_MK + ".AndroidBuilder"
        val buildSpec = File(directory(), Utils.BUILDSPEC_MK)
        if (!buildSpec.exists() && buildSpec.createNewFile()) {
            val bos = BufferedOutputStream(SafeFileOutputStream(buildSpec)).bufferedWriter()
            bos.write(FIRST_LINE)
            bos.newLine()
            bos.write("# If you don't want to associate AndroidBuilder anymore,\n")
            bos.write("# delete following line.\n")
            bos.write("-include $specFileName\n")
            bos.close()
        }

        val buildSpecForBuilder = File(directory(), specFileName)
        buildSpecForBuilder.delete()
        if (buildSpecForBuilder.createNewFile()) {
            val bos = BufferedOutputStream(SafeFileOutputStream(buildSpecForBuilder)).bufferedWriter()
            bos.write(sb.toString())
            bos.close()
        }
    }
}