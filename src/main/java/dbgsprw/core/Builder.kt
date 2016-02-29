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

import java.io.File
import java.util.*

/**
 * Created by ganadist on 16. 2. 25.
 */
class Builder : CommandExecutor() {
    private var mTargetProduct = ""
    private var mBuildVariant = ""
    private var mOutDir = ""
    private var mTarget = ""
    private var mTargetSdk = false
    private var mOneShotMakefile: String? = null
    private val CM_PRODUCT_PREFIX = "cm_"

    fun setAndroidJavaHome(home: String) {
        setenv("ANDROID_JAVA_HOME", home)
        val path = getenv("PATH")
        val jdkBinPath = arrayOf(home, "bin").joinToString(File.separator)
        if (!path.startsWith(jdkBinPath)) {
            setenv("PATH", jdkBinPath + File.pathSeparator + path)
        }
    }

    fun setTargetProduct(product: String) {
        mTargetProduct = product
        updateOutDir()
    }

    fun setBuildVariant(variant: String) {
        mBuildVariant = variant
        updateOutDir()
    }

    fun setTarget(target: String) {
        mTarget = target
        mOneShotMakefile = null
        mTargetSdk = mTarget.contains("sdk")
        updateOutDir()
    }

    fun setOneShot(makefile: String) {
        mTarget = "all_modules"
        mOneShotMakefile = makefile
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
            findProductOutPath()
        }
    }

    fun buildMakeCommand(jobs: Int, verbose: Boolean, extras: String?): ArrayList<String> {
        val command = ArrayList<String>()
        command.add("make")
        if (jobs > 1) {
            command.add("-j$jobs")
        }
        command.add("TARGET_PRODUCT=$mTargetProduct")
        if (mTargetProduct.startsWith(CM_PRODUCT_PREFIX)) {
            val cmBuild = mTargetProduct.substring(CM_PRODUCT_PREFIX.length)
            command.add("CM_BUILD=$cmBuild")
            command.add("BUILD_WITH_COLORS=0") // turn off color
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
            command.addAll(extras!!.split("\\s+"))
        }

        return command
    }

    fun runCombo(listener: ComboMenuListener): Process {

        val command = arrayOf("source build/envsetup.sh > /dev/null",
                "printf '%s\n' \${LUNCH_MENU_CHOICES[@]} | cut -f 1 -d - | sort -u").asList()

        return run(command, object : CommandHandler {
            override fun onOut(line: String) {
                listener.onTargetAdded(line)
            }

            override fun onExit(code: Int) {
                listener.onCompleted()
            }
        }, true)
    }

    private fun findProductOutPath(): Process {
        val selectedTarget = mTargetProduct + '-' + mBuildVariant
        Utils.log("builder", "target = $selectedTarget out_dir = $mOutDir")
        val command = arrayOf("source build/envsetup.sh > /dev/null",
                "lunch $selectedTarget > /dev/null",
                "echo \$ANDROID_PRODUCT_OUT").asList()

        return run(command, object : CommandHandler {
            override fun onOut(line: String) {
                Utils.log("builder", "ANDROID_PRODUCT_OUT set " + line)
                setenv("ANDROID_PRODUCT_OUT", line);
                mOutPathListener.onAndroidProductOutChanged(line)
            }
        }, true)
    }

    private var mOutPathListener: OutPathListener = object : OutPathListener {}

    fun setOutPathListener(listener: OutPathListener) {
        mOutPathListener = listener
    }

    interface OutPathListener {
        fun onOutDirChanged(path: String) {
        }

        fun onAndroidProductOutChanged(path: String) {
        }
    }

    interface ComboMenuListener {
        fun onTargetAdded(target: String)
        fun onCompleted()
    }
}
