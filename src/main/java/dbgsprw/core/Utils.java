/*
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
 */

package dbgsprw.core;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;

public class Utils {
    public static final String ANDROID_MK = "Android.mk";
    public static final String MAKEFILE = "Makefile";
    public static final String ENVSETUP_SH = pathJoin("build", "envsetup.sh");
    public static final String VERSION_DEFAULT_MK = pathJoin("build", "core", "version_defaults.mk");
    public static final String BUILDSPEC_MK = "buildspec.mk";
    private static final String EMPTY = "";

    private static final String BUILDER_PROPERTIES_PATH = "properties/builder.properties";
    private static final String FASTBOOT_PROPERTIES_PATH = "properties/fastboot_argument.properties";
    private static final Properties sBuilderProperties;
    public static final Properties sFastbootProperties;
    public static final String[] sTargets;
    public static final String[] sVariants;
    public static final String[] sAdbSyncArguments;
    public static final String[] sFastbootArguments;

    static {
        final PropertiesLoader pl = new PropertiesLoader();
        sBuilderProperties = pl.getProperties(BUILDER_PROPERTIES_PATH);
        sTargets = sBuilderProperties.getProperty("targets", "").split(",");
        sVariants = sBuilderProperties.getProperty("variants", "").split(",");
        sAdbSyncArguments = sBuilderProperties.getProperty("adbsync_args", "").split(",");
        sFastbootProperties = pl.getProperties(FASTBOOT_PROPERTIES_PATH);
        final ArrayList<String> argsList = new ArrayList<String>(sFastbootProperties.stringPropertyNames());
        Collections.sort(argsList);
        String[] fastbootArgs = new String[argsList.size()];
        sFastbootArguments = argsList.toArray(fastbootArgs);
    }

    public static String join(final char separator, final Object[] array) {
        if (array == null) {
            return null;
        }
        if (array.length == 0) {
            return EMPTY;
        }

        final StringBuilder sb = new StringBuilder();
        final int startIndex = 0;
        for (int i = startIndex; i < array.length; i++) {
            if (i > startIndex) {
                sb.append(separator);
            }
            if (array[i] != null) {
                sb.append(array[i]);
            }
        }
        return sb.toString();
    }

    public static String join(final char seperator, String... array) {
        return join(seperator, (Object[]) array);
    }

    public static String pathJoin(String... paths) {
        return join(File.separatorChar, paths);
    }

    public static String findAndroidMkOnParent(String root, String filename) {
        File file = new File(filename);
        if (!file.getAbsolutePath().startsWith(root)) {
            return null; // filename is out of project root directory.
        }
        while (true) {
            File parent = file.getParentFile();
            File AndroidMk = new File(parent, ANDROID_MK);
            if (AndroidMk.exists()) {
                String path = AndroidMk.getPath();
                return path.substring(root.length() + 1, path.length() - ANDROID_MK.length() - 1);
            }
            if (root.equals(parent.getAbsolutePath())) {
                return null; // cannot find Android.mk
            }
            file = parent;
        }
    }

    public static void runOnUi(Runnable runnable) {
        final Application app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) {
            runnable.run();
        } else {
            app.invokeLater(runnable);
        }
    }
}
