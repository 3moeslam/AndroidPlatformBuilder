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
 *
 */

package dbgsprw.action;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ShortcutSet;
import com.intellij.openapi.components.ServiceManager;
import dbgsprw.app.BuildToolbar;
import dbgsprw.view.Notify;

/**
 * Created by ganadist on 16. 3. 1.
 */
public abstract class MenuAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        BuildToolbar toolbar = ServiceManager.getService(e.getProject(), BuildToolbar.class);

        if (toolbar == null) {
            Notify.show("Cannot start to build", "Please Enable Tool Window First.\nSelect View -> Tool Windows -> Android Builder",
                    NotificationType.ERROR);
        }
        doAction(toolbar);
    }

    abstract void doAction(BuildToolbar toolbar);

    public void setShortcutSet(ShortcutSet shortcutSet) {
        super.setShortcutSet(shortcutSet);
    }
}

