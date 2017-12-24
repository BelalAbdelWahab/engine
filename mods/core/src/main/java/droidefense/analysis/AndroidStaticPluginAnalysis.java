package droidefense.analysis;

import com.droidefense.log4j.Log;
import com.droidefense.log4j.LoggerType;
import droidefense.analysis.base.AbstractAndroidAnalysis;
import droidefense.handler.DirScannerHandler;
import droidefense.handler.FileIOHandler;
import droidefense.handler.base.DirScannerFilter;
import droidefense.sdk.AbstractStaticPlugin;
import droidefense.sdk.helpers.InternalConstant;
import droidefense.sdk.model.base.ExecutionTimer;
import droidefense.sdk.model.io.AbstractHashedFile;

import java.io.File;
import java.util.ArrayList;

/**
 * Created by sergio on 17/2/16.
 */
public final class AndroidStaticPluginAnalysis extends AbstractAndroidAnalysis {

    public transient static final String PLUGIN_PACKAGE_NAME = "external.plugins.collection.sttc.";

    public AndroidStaticPluginAnalysis() {
        timeStamp = new ExecutionTimer();
    }

    @Override
    public boolean analyze() {
        executionSuccessful = false;
        Log.write(LoggerType.TRACE, "\n\n --- Running Android static plugin analysis ---\n\n");
        File plFolder = FileIOHandler.getStaticPluginsFolderFile();
        if (plFolder.exists()) {
            DirScannerHandler scanner = new DirScannerHandler(plFolder, false, new DirScannerFilter() {
                @Override
                public boolean addFile(File f) {
                    return f.getName().endsWith(InternalConstant.COMPILED_JAVA_EXTENSION);
                }
            });
            scanner.doTheJob();
            ArrayList<AbstractHashedFile> pluginsList = scanner.getFiles();
            for (AbstractHashedFile plugin : pluginsList) {
                //run each plugin in a different thread
                String pluginName = plugin.getName();
                if (pluginName.endsWith(InternalConstant.COMPILED_JAVA_EXTENSION)) {
                    System.out.println();
                    Log.write(LoggerType.TRACE, " ######## PLUGIN ########");
                    Log.write(LoggerType.TRACE, plugin.getAbsolutePath());
                    Log.write(LoggerType.TRACE, " ######## PLUGIN ########");
                    System.out.println();
                    Class aClass;
                    try {
                        ClassLoader classLoader = this.getClass().getClassLoader();
                        aClass = classLoader.loadClass(PLUGIN_PACKAGE_NAME + pluginName.replace(".class", ""));
                        Log.write(LoggerType.TRACE, "Executing " + aClass.getName());
                        AbstractStaticPlugin staticPlugin = (AbstractStaticPlugin) aClass.newInstance();

                        staticPlugin.setApk(apkFile);
                        staticPlugin.setCurrentProject(currentProject);
                        staticPlugin.analyze();

                        //add result to currentProject
                        currentProject.addStaticPlugin(staticPlugin);
                    } catch (Exception e) {
                        Log.write(LoggerType.FATAL, "Fatal error while executing external plugin", e.getLocalizedMessage());
                        addError(e);
                    }
                } else {
                    //todo load jar content using reflection. for more sofisticated plugins
                    Log.write(LoggerType.TRACE, plugin.getAbsolutePath());
                }
            }
            executionSuccessful = true;
        }
        Log.write(LoggerType.TRACE, "\n--- RUNNING PLUGINS (DONE)---\n");
        //stop timer
        stop();
        this.timeStamp.stop();
        return executionSuccessful;
    }

    @Override
    public String getName() {
        return "Android static plugin analysis";
    }

}
