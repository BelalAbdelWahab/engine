package droidefense.worker.parser;

import apkr.external.modules.helpers.log4j.Log;
import apkr.external.modules.helpers.log4j.LoggerType;
import droidefense.mod.vfs.model.impl.VirtualFile;
import droidefense.sdk.helpers.InternalConstant;
import droidefense.sdk.model.base.DroidefenseProject;
import droidefense.sdk.model.io.AbstractHashedFile;
import droidefense.sdk.model.io.LocalApkFile;
import droidefense.sdk.model.io.VirtualHashedFile;
import droidefense.worker.handler.FileIOHandler;
import droidefense.worker.parser.base.AbstractFileParser;

import java.io.File;
import java.util.ArrayList;

/**
 * Created by sergio on 21/2/16.
 */
public class APKMetaParser extends AbstractFileParser {

    public APKMetaParser(LocalApkFile apk, DroidefenseProject currentProject) {
        super(apk, currentProject);
    }

    @Override
    public void parserCode() {

        Log.write(LoggerType.INFO, "\n\nReading app metadata...\n");

        //get app files
        ArrayList<VirtualFile> files = currentProject.getAppFiles();
        ArrayList<AbstractHashedFile> dexList = new ArrayList<>();

        ArrayList<AbstractHashedFile> assetFiles = new ArrayList<>();
        ArrayList<AbstractHashedFile> libFiles = new ArrayList<>();
        ArrayList<AbstractHashedFile> rawFiles = new ArrayList<>();
        ArrayList<AbstractHashedFile> otherFiles = new ArrayList<>();

        //manifest file
        AbstractHashedFile manifest = null;
        AbstractHashedFile certFile = null;
        for (VirtualFile r : files) {
            //count dex files and search manifest file
            if (r.getName().toLowerCase().endsWith(InternalConstant.DEX_EXTENSION)) {
                dexList.add(new VirtualHashedFile(r, true));
            } else if (r.getName().toLowerCase().endsWith(InternalConstant.CERTIFICATE_EXTENSION)) {
                certFile = new VirtualHashedFile(r, true);
            } else if (r.getName().equals(InternalConstant.ANDROID_MANIFEST) && manifest == null) {
                manifest = new VirtualHashedFile(r, true);
            } else if (r.getPath().contains(File.separator + "assets" + File.separator)) {
                assetFiles.add(new VirtualHashedFile(r, true));
            } else if (r.getPath().contains(File.separator + "lib" + File.separator)) {
                libFiles.add(new VirtualHashedFile(r, true));
            } else if (r.getPath().contains(File.separator + "res" + File.separator + "raw")) {
                rawFiles.add(new VirtualHashedFile(r, true));
            } else {
                otherFiles.add(new VirtualHashedFile(r, true));
            }
        }

        //set dex files
        this.currentProject.setDexList(dexList);

        //set assets, raw and lib files
        this.currentProject.setRawFiles(rawFiles);
        this.currentProject.setAssetsFiles(assetFiles);
        this.currentProject.setLibFiles(libFiles);
        this.currentProject.setOtherFiles(otherFiles);

        //set number of dex files
        this.currentProject.setNumberofDex(dexList.size());

        //set manifest file
        this.currentProject.setManifestFile(manifest);

        //set certificate file
        this.currentProject.setCertificateFile(certFile);

        //set currentProject folder name
        this.currentProject.setProjectFolderName(FileIOHandler.getUnpackOutputPath(apk));
    }
}
