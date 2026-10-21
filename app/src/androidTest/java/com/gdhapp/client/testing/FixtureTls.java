package com.gdhapp.client.testing;

import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.zip.ZipFile;
import javax.net.ssl.*;

/** Fixture-only certificate store, shared with the shell-UID bulk-transfer helper. */
public final class FixtureTls {
    public final SSLContext context;
    public final X509TrustManager trust;
    public FixtureTls(String testApk) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null);
        try (ZipFile zip = new ZipFile(testApk)) {
            java.security.cert.Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(zip.getInputStream(zip.getEntry("assets/fixture-cert.pem")));
            String pem = new String(zip.getInputStream(zip.getEntry("assets/fixture-key.pem")).readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
            byte[] bytes = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
            java.security.PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(bytes));
            store.setKeyEntry("fixture", key, new char[0], new java.security.cert.Certificate[] {cert});
            store.setCertificateEntry("trusted-fixture", cert);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, new char[0]);
        TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusts.init(store);
        trust = (X509TrustManager) trusts.getTrustManagers()[0];
        context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), new TrustManager[] {trust}, null);
    }
}
