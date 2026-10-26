import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/** Signs an APK (v1+v2+v3) with the bundled PKCS12 key using apksig. Usage: Sign in.apk out.apk keystore.p12 pass alias */
public class Sign {
    public static void main(String[] a) throws Exception {
        char[] pw = a[3].toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(a[2])) { ks.load(in, pw); }
        PrivateKey key = (PrivateKey) ks.getKey(a[4], pw);
        X509Certificate cert = (X509Certificate) ks.getCertificate(a[4]);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder(a[4], key, List.of(cert)).build();
        new ApkSigner.Builder(List.of(sc))
                .setInputApk(new File(a[0]))
                .setOutputApk(new File(a[1]))
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
        System.out.println("signed " + a[1]);
    }
}
