package tv.luma;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Check app identity before granting Android's installer temporary APK access. */
final class UpdatePackage {
    static String currentVersion(Context context){
        try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException impossible){throw new AssertionError(impossible);}
    }
    static long code(PackageInfo info){return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    private static Set<String> signers(PackageInfo info)throws AppUpdater.UpdateException{
        Signature[] signatures=Build.VERSION.SDK_INT>=28?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;
        if(signatures==null||signatures.length==0)throw new AppUpdater.UpdateException("The APK has no readable signing certificate.");
        Set<String> result=new HashSet<>();try{for(Signature signature:signatures)result.add(AppUpdater.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}return result;
    }
    static void validate(Context context,File file,AppUpdater.Release release)throws IOException{
        PackageManager pm=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags),installed;
        try{installed=pm.getPackageInfo(context.getPackageName(),flags);}catch(PackageManager.NameNotFoundException impossible){throw new AssertionError(impossible);}
        if(archive==null||archive.applicationInfo==null)throw new AppUpdater.UpdateException("The downloaded APK could not be read.");
        if(!context.getPackageName().equals(archive.packageName))throw new AppUpdater.UpdateException("This APK is for a different app.");
        if(code(archive)<=code(installed))throw new AppUpdater.UpdateException("This APK is not newer than your installed app.");
        if(!release.version.equals(archive.versionName))throw new AppUpdater.UpdateException("The APK version does not match its release.");
        if(archive.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)throw new AppUpdater.UpdateException("This update needs a newer Android version.");
        if(!signers(installed).equals(signers(archive)))throw new AppUpdater.UpdateException("This APK uses a different signing key and cannot update your installation.");
    }
    static Intent permissionIntent(Context context){return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.getPackageName()));}
    static Intent installIntent(Context context,File file){
        Uri uri=FileProvider.getUriForFile(context,context.getPackageName()+".updates",file);
        Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true);
        intent.setClipData(ClipData.newRawUri("Luma TV update",uri));return intent;
    }
}
