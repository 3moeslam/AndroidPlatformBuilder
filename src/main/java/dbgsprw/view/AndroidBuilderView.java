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
 */

package dbgsprw.view;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.ModuleServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import dbgsprw.app.*;
import dbgsprw.core.Utils;
import dbgsprw.device.Device;
import dbgsprw.device.DeviceManager;
import dbgsprw.exception.FileManagerNotFoundException;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by ganadist on 16. 2. 23.
 */
public class AndroidBuilderView implements BuildToolbar,
        BuildService.OutPathListener {
    private JPanel mAndroidBuilderContent;
    private JPanel mMakeOptionPanel;
    private JLabel mTargetLabel;
    private JComboBox<String> mTargetComboBox;
    private JRadioButton mMmRadioButton;
    private JRadioButton mMakeRadioButton;
    private JComboBox<String> mExtraArgumentsComboBox;
    private JLabel mTargetDirLabel;
    private JComboBox<String> mTargetDirComboBox;
    private JCheckBox mVerboseCheckBox;
    private JButton mMakeButton;
    private JButton mMakeStopButton;
    private JSpinner mJobSpinner;
    private JPanel mFlashOptionPanel;
    private JComboBox<String> mDeviceListComboBox;
    private JRadioButton mAdbSyncRadioButton;
    private JRadioButton mFastbootRadioButton;
    private JComboBox<String> mWriteArgumentComboBox;
    private JCheckBox mWipeCheckBox;
    private JButton mRebootButton;
    private JButton mRebootBootloaderButton;
    private JButton mFlashButton;
    private JButton mSyncButton;
    private JButton mFlashStopButton;
    private JComboBox<String> mProductComboBox;
    private JComboBox<String> mVariantComboBox;
    private JLabel mResultPathValueLabel;
    private JButton mOpenDirectoryButton;

    private final static Logger LOG = Logger.getInstance(AndroidBuilderView.class);
    private final static String CURRENT_PATH = "Current Path";
    private final static String ADB_PROPERTIES_PATH = "properties/adb_sync_argument.properties";
    private final static String FASTBOOT_PROPERTIES_PATH = "properties/fastboot_argument.properties";
    private final static String TARGET_PROPERTIES_PATH = "properties/target_argument.properties";
    private final static String VARIANT_PROPERTIES_PATH = "properties/variant_argument.properties";

    private ButtonGroup mMakeButtonGroup;
    private ButtonGroup mFlashButtonGroup;

    private String mProjectPath;
    private Project mProject;
    private File mProductOut;

    private StateStore mState;
    private static final String TOOLBAR_WINDOW_ID = "Android Builder";

    private static final ArgumentProperties sAdbSyncProperties;
    private static final ArgumentProperties sFastbootProperties;
    private static final ArgumentProperties sTargetProperties;
    private static final ArgumentProperties sVariantProperties;

    static {
        ArgumentPropertiesManager argumentPropertiesManager = new ArgumentPropertiesManager();
        sAdbSyncProperties = argumentPropertiesManager.loadProperties(ADB_PROPERTIES_PATH);
        sFastbootProperties = argumentPropertiesManager.loadProperties(FASTBOOT_PROPERTIES_PATH);
        sTargetProperties = argumentPropertiesManager.loadProperties(TARGET_PROPERTIES_PATH);
        sVariantProperties = argumentPropertiesManager.loadProperties(VARIANT_PROPERTIES_PATH);
    }

    AndroidBuilderView(Project project) {
        LOG.info("init");

        ToolWindowManagerEx wm = ToolWindowManagerEx.getInstanceEx(project);
        ToolWindow window = wm.registerToolWindow(TOOLBAR_WINDOW_ID, false, ToolWindowAnchor.RIGHT,
                project, true);

        mProject = project;
        mProjectPath = mProject.getBasePath();

        mState = StateStore.getState(project);

        final HistoryComboModel history = new HistoryComboModel();
        mProductComboBox.setPrototypeDisplayValue("XXXXXXXXX");
        mProductComboBox.setModel(history);

        getBuilder().runCombo(new BuildService.ComboMenuListener() {
            @Override
            public void onTargetAdded(@NotNull String target) {
                history.addElement(target);
            }

            @Override
            public void onCompleted() {
                mProductComboBox.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent actionEvent) {
                        final String product = (String) mProductComboBox.getSelectedItem();
                        if (product != null) {
                            getBuilder().setTargetProduct(product);
                            mState.mProduct = product;
                        }
                    }
                });

                int index = 0;
                if (mState.mProduct != null) {
                    index = history.getIndexOf(mState.mProduct);
                    if (index < 0) index = 0;
                }
                mProductComboBox.setSelectedIndex(index); // set explicitly for fire action
            }
        });

        getBuilder().setOutPathListener(this);

        ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
        Content content = contentFactory.createContent(mAndroidBuilderContent, "", false);
        window.getContentManager().addContent(content);

        initMakePanelComboBoxes();
        initMakePanelButtons();
        initMakePanelRadioButtons();

        initFlashPanelRadioButtons();
        initFlashButtons();

        ServiceManager.getService(DeviceManager.class).addDeviceStateListener(this);
    }

    private boolean setPartialBuild(String path) {
        if (CURRENT_PATH.equals(path)) {
            Document currentDoc;
            VirtualFile currentPath;
            try {
                currentDoc = FileEditorManager.getInstance(mProject).getSelectedTextEditor().
                        getDocument();
                currentPath = FileDocumentManager.getInstance().getFile(currentDoc);
                path = Utils.findAndroidMkOnParent(mProjectPath, currentPath.getPath());
            } catch (NullPointerException e) {
                Notify.show("There is no opened file on editor.", NotificationType.ERROR);
                return false;
            }

            if (path == null) {
                Notify.show("cannot find Android.mk", NotificationType.ERROR);
                return false;
            }
            ((TargetDirHistoryComboModel) mTargetDirComboBox.getModel()).addElement(path);
        }
        getBuilder().setOneShotDirectory(path);
        return true;
    }

    private void doBuild() {
        saveCurrentProject();
        final BuildService builder = getBuilder();

        if (!builder.canBuild()) {
            Notify.show("Other job is processing.", NotificationType.ERROR);
            return;
        }

        int jobs = (Integer) mJobSpinner.getValue();
        boolean verbose = mVerboseCheckBox.isSelected();
        String extras = (String) mExtraArgumentsComboBox.getSelectedItem();

        builder.build(jobs, verbose, extras, new BuildConsole.ExitListener() {
                    @Override
                    public void onExit() {
                        mMakeButton.setVisible(true);
                        mMakeStopButton.setVisible(false);
                    }
                }
        );

        mMakeButton.setVisible(false);
        mMakeStopButton.setVisible(true);
    }

    @Override
    public void doMake() {
        mMakeRadioButton.doClick();
        getBuilder().setTarget((String) mTargetComboBox.getSelectedItem());
        doBuild();
    }

    @Override
    public void doMm() {
        mMmRadioButton.doClick();
        if (setPartialBuild(CURRENT_PATH)) {
            doBuild();
        }
    }

    private void doOpenOutDirectory() {
        if (!mProductOut.exists()) {
            if (!mProductOut.mkdirs()) {
                Notify.show("cannot open ANDROID_PRODUCT_OUT directory.", NotificationType.ERROR);
            }
        }
        try {
            DirectoryOpener.openDirectory(mProductOut.getPath());
        } catch (FileManagerNotFoundException e) {
            Notify.show("can't find file manager command.", NotificationType.ERROR);
        }
    }

    private void initMakePanelButtons() {
        mOpenDirectoryButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                doOpenOutDirectory();
            }
        });

        mMakeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                if (mMmRadioButton.isSelected()) {
                    String path = (String) mTargetDirComboBox.getSelectedItem();
                    if (!setPartialBuild(path)) {
                        return;
                    }
                } else {
                    getBuilder().setTarget((String) mTargetComboBox.getSelectedItem());
                }

                doBuild();
            }
        });
        mMakeStopButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                getBuilder().stopBuild();
                mMakeButton.setVisible(true);
                mMakeStopButton.setVisible(false);
            }
        });
        mMakeStopButton.setVisible(false);
    }

    private void saveCurrentProject() {
        FileDocumentManager.getInstance().saveAllDocuments();
    }

    private void initMakePanelRadioButtons() {
        mMakeButtonGroup = new ButtonGroup();
        mMakeButtonGroup.add(mMakeRadioButton);
        mMakeButtonGroup.add(mMmRadioButton);

        mMakeRadioButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                mTargetComboBox.setVisible(true);
                mTargetLabel.setVisible(true);
                mTargetDirLabel.setVisible(false);
                mTargetDirComboBox.setVisible(false);
            }
        });

        mMmRadioButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                mTargetComboBox.setVisible(false);
                mTargetLabel.setVisible(false);
                mTargetDirLabel.setVisible(true);
                mTargetDirComboBox.setVisible(true);
            }
        });

        mMakeRadioButton.doClick();
    }

    public void addPropertiesToComboBox(ArgumentProperties properties, JComboBox jComboBox) {
        jComboBox.removeAllItems();
        for (String name : properties.getPropertyNames()) {
            jComboBox.addItem(name);
        }
    }

    private void initMakePanelComboBoxes() {
        HistoryComboModel history;

        addPropertiesToComboBox(sTargetProperties, mTargetComboBox);

        mTargetComboBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                final String target = (String) mTargetComboBox.getSelectedItem();
                getBuilder().setTarget(target);
                mState.mTarget = target;
            }
        });
        mTargetComboBox.setSelectedItem(mState.mTarget);

        mTargetDirComboBox.setModel(new TargetDirHistoryComboModel(CURRENT_PATH));
        mTargetDirComboBox.setPrototypeDisplayValue("XXXXXXXXX");

        addPropertiesToComboBox(sVariantProperties, mVariantComboBox);
        mVariantComboBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                final String buildVariant = (String) mVariantComboBox.getSelectedItem();
                getBuilder().setBuildVariant(buildVariant);
                mState.mBuildVariant = buildVariant;
            }
        });
        mVariantComboBox.setSelectedItem(mState.mBuildVariant);

        history = new HistoryComboModel();
        history.addElement(mState.mExtras);
        mExtraArgumentsComboBox.setModel(history);
        mExtraArgumentsComboBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                mState.mExtras = (String) mExtraArgumentsComboBox.getSelectedItem();
            }
        });

        final int numberOfCpus = Runtime.getRuntime().availableProcessors();
        final int initialJobNumber = (numberOfCpus > 4) ? numberOfCpus - 1 : numberOfCpus;

        SpinnerNumberModel model = new SpinnerNumberModel(initialJobNumber,
                1, numberOfCpus, 1);
        mJobSpinner.setModel(model);
    }

    private void initFlashPanelRadioButtons() {
        mFlashButtonGroup = new ButtonGroup();
        mFlashButtonGroup.add(mFastbootRadioButton);
        mFlashButtonGroup.add(mAdbSyncRadioButton);

        mFastbootRadioButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                mWipeCheckBox.setEnabled(true);
                mWriteArgumentComboBox.addActionListener(mFastbootArgumentListener);
                addPropertiesToComboBox(sFastbootProperties, mWriteArgumentComboBox);
                changeFlashButton();
            }
        });

        mAdbSyncRadioButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                mWipeCheckBox.setEnabled(false);
                mWriteArgumentComboBox.removeActionListener(mFastbootArgumentListener);
                addPropertiesToComboBox(sAdbSyncProperties, mWriteArgumentComboBox);
                changeFlashButton();
            }
        });

        mFastbootRadioButton.doClick();

        mDeviceListComboBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                changeFlashButton();
            }
        });

        mWriteArgumentComboBox.setSelectedItem("flashall");
    }

    private void doFlash() {
        boolean wipe = mWipeCheckBox.isSelected();
        Device device = getSelectedDevice();
        String partition = (String) mWriteArgumentComboBox.getSelectedItem();
        String filename = "";

        // FIXME
        // needs clean up
        if (partition.equals("update")) {
            partition = "";
            filename = fastBootArgumentComboBoxInterpreter("update")[1];
        } else if (partition.equals("bootloader")) {
            filename = fastBootArgumentComboBoxInterpreter(partition)[2];
        }

        getBuilder().sync(device, partition, filename, wipe, new BuildConsole.ExitListener() {
                    @Override
                    public void onExit() {
                        boolean isFastBootRadioButtonClicked = mFastbootRadioButton.isSelected();
                        mFlashButton.setVisible(isFastBootRadioButtonClicked);
                        mSyncButton.setVisible(!isFastBootRadioButtonClicked);
                        mFlashStopButton.setVisible(false);
                    }
                }
        );

        mFlashButton.setVisible(false);
        mSyncButton.setVisible(false);
        mFlashStopButton.setVisible(true);
    }

    private void doSync() {
        String argument = mWriteArgumentComboBox.getSelectedItem().toString();
        if ("All".equals(argument)) {
            argument = "";
        }

        Device device = getSelectedDevice();

        getBuilder().sync(device, argument, "", false, new BuildConsole.ExitListener() {
                    @Override
                    public void onExit() {
                        boolean isFastBootRadioButtonClicked = mFastbootRadioButton.isSelected();
                        mFlashButton.setVisible(isFastBootRadioButtonClicked);
                        mSyncButton.setVisible(!isFastBootRadioButtonClicked);
                        mFlashStopButton.setVisible(false);
                    }
                }
        );

        mFlashButton.setVisible(false);
        mSyncButton.setVisible(false);
        mFlashStopButton.setVisible(true);
    }

    private boolean prepareWrite() {
        if (getSelectedDevice() == null) {
            Notify.show("Device is not selected", NotificationType.ERROR);
            return false;
        } else if (!mProductOut.exists()) {
            Notify.show("There is no output directory.\nPlease build first.", NotificationType.ERROR);
            return false;
        } else if (!getBuilder().canSync()) {
            Notify.show("Other job is processing.", NotificationType.ERROR);
            return false;
        }
        return true;
    }

    private void initFlashButtons() {
        mFlashButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                if (prepareWrite()) {
                    doFlash();
                }
            }
        });

        mSyncButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                if (prepareWrite()) {
                    doSync();
                }
            }
        });

        mRebootBootloaderButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                getSelectedDevice().reboot();
            }
        });

        mRebootButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                getSelectedDevice().reboot();
            }
        });

        mFlashStopButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                getBuilder().stopSync();
                boolean isFastBootRadioButtonClicked = mFastbootRadioButton.isSelected();
                mFlashButton.setVisible(isFastBootRadioButtonClicked);
                mSyncButton.setVisible(!isFastBootRadioButtonClicked);

                mFlashStopButton.setVisible(false);
            }
        });
        mFlashStopButton.setVisible(false);

    }

    private ActionListener mFastbootArgumentListener = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent actionEvent) {
            String argument = (String) mWriteArgumentComboBox.getSelectedItem();
            if ("update".equals(argument) || "bootloader".equals(argument)) {
                JFileChooser jFileChooser = new JFileChooser();
                if (mProductOut.exists()) {
                    jFileChooser.setCurrentDirectory(mProductOut);
                } else {
                    jFileChooser.setCurrentDirectory(new File(mProjectPath));
                }

                final boolean update = "update".equals(argument);

                final String msg = update ?
                        "Choose Update Package File" : "Choose Bootloader Package File";

                if (jFileChooser.showDialog(mAndroidBuilderContent, msg) ==
                        JFileChooser.APPROVE_OPTION) {
                    File selectedFile = jFileChooser.getSelectedFile();
                    if (selectedFile.exists()) {
                        try {
                            final String filePath = selectedFile.getCanonicalPath();
                            final String value = update ?
                                    ("update\t" + filePath) :
                                    ("flash\tbootloader\t" + filePath);
                            sFastbootProperties.setProperty(argument, value);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    };

    private String[] fastBootArgumentComboBoxInterpreter(String argument) {
        return sFastbootProperties.getArguments(argument, "\t");
    }

    private void changeFlashButton() {
        Device device = getSelectedDevice();
        if (device == null) {
            mRebootButton.setVisible(false);
            mSyncButton.setVisible(false);
            mRebootBootloaderButton.setVisible(false);
            mFlashButton.setVisible(false);
        } else if (mFastbootRadioButton.isSelected()) {
            mRebootButton.setVisible(false);
            mSyncButton.setVisible(false);
            switch (device.getType()) {
                case FASTBOOT:
                    mRebootBootloaderButton.setVisible(false);
                    mFlashButton.setVisible(true);
                    break;
                case ADB:
                    mRebootBootloaderButton.setVisible(true);
                    mFlashButton.setVisible(false);
                    break;
            }
        } else {
            mRebootBootloaderButton.setVisible(false);
            mFlashButton.setVisible(false);
            switch (device.getType()) {
                case FASTBOOT:
                    mRebootButton.setVisible(true);
                    mSyncButton.setVisible(false);
                    break;
                case ADB:
                    mRebootButton.setVisible(false);
                    mSyncButton.setVisible(true);
                    break;
            }
        }
    }

    @Override
    public void onOutDirChanged(String path) {
        mOpenDirectoryButton.setEnabled(false);
        mFlashButton.setEnabled(false);
        mSyncButton.setEnabled(false);
        mResultPathValueLabel.setText(path);
        ModuleServiceManager.getService(ModuleMonitorKt.getAndroidModule(mProject),
                ModuleMonitor.class).onOutDirChanged(path);
    }

    @Override
    public void onAndroidProductOutChanged(String path) {
        mProductOut = new File(path);
        mOpenDirectoryButton.setEnabled(true);
        mFlashButton.setEnabled(true);
        mSyncButton.setEnabled(true);
    }

    private Map<String, Device> mDevices = new HashMap<String, Device>();

    @Override
    public void onDeviceAdded(@NotNull Device device) {
        String name = device.getDeviceName();
        LOG.info("device is added: " + name);
        mDevices.put(name, device);
        mDeviceListComboBox.addItem(name);
        changeFlashButton();
    }

    @Override
    public void onDeviceRemoved(@NotNull Device device) {
        String name = device.getDeviceName();
        LOG.info("device is removed: " + name);
        mDeviceListComboBox.removeItem(name);
        mDevices.remove(name);
        changeFlashButton();
    }

    private Device getSelectedDevice() {
        String deviceName = (String) mDeviceListComboBox.getSelectedItem();
        return (deviceName == null) ? null : mDevices.get(deviceName);
    }

    private BuildService getBuilder() {
        return ServiceManager.getService(mProject, BuildService.class);
    }

    @Override
    public void dispose() {
        LOG.info("dispose");
        ServiceManager.getService(DeviceManager.class).removeDeviceStateListener(this);
        getBuilder().setOutPathListener(null);
        ToolWindowManagerEx.getInstanceEx(mProject).unregisterToolWindow(TOOLBAR_WINDOW_ID);
    }
}
