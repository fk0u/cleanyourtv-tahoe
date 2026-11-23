package tv.clean.home;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import tech.kousozo.tahoetv.core.ApkTrust;

/** Reads package name and signing certificates of an installed app and of a downloaded APK, for [ApkTrust]. */
final class ApkCheck {
    private ApkCheck() {}

    /** Throws when [apk] is not a genuine update of this app (other package, unsigned, or signed by someone else). */
    static void requireGenuineUpdate(Context c, File apk, String message) throws Exception {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo mine = pm.getPackageInfo(c.getPackageName(), flags);
        PackageInfo cand = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        ApkTrust.Verdict v = ApkTrust.verdict(c.getPackageName(), cand == null ? null : cand.packageName,
                current(mine), cand == null ? new HashSet<>() : current(cand), cand == null ? new HashSet<>() : history(cand));
        if (v != ApkTrust.Verdict.OK) {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            tech.kousozo.tahoetv.core.DiagLog.INSTANCE.add("update", "refused " + apk.getName() + ": " + v);
            throw new SecurityException(message);
        }
    }

    private static Set<String> current(PackageInfo pi) throws Exception {
        Set<String> out = new HashSet<>();
        if (Build.VERSION.SDK_INT >= 28) {
            SigningInfo si = pi.signingInfo;
            if (si != null) for (Signature s : si.getApkContentsSigners()) out.add(sha256(s));
        } else if (pi.signatures != null) for (Signature s : pi.signatures) out.add(sha256(s));
        return out;
    }

    private static Set<String> history(PackageInfo pi) throws Exception {
        Set<String> out = new HashSet<>();
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null && !pi.signingInfo.hasMultipleSigners()) {
            Signature[] h = pi.signingInfo.getSigningCertificateHistory();
            if (h != null) for (Signature s : h) out.add(sha256(s));
        }
        return out;
    }

    private static String sha256(Signature s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
