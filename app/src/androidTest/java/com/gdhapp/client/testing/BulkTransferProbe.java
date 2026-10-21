package com.gdhapp.client.testing;

import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.io.InputStream;
import java.util.zip.CRC32;

/** Runs under app_process as shell UID, so traffic really enters the app's VpnService. */
public final class BulkTransferProbe {
    public static void main(String[] args) throws Exception {
        int size = Integer.parseInt(args[0]);
        boolean https = args.length > 1 && args[1].equals("https");
        HttpURLConnection connection = (HttpURLConnection) new URL((https ? "https" : "http") + "://10.0.2.2:18082/" + size).openConnection(Proxy.NO_PROXY);
        if (https) {
            FixtureTls tls = new FixtureTls(System.getenv("CLASSPATH"));
            ((javax.net.ssl.HttpsURLConnection) connection).setSSLSocketFactory(tls.context.getSocketFactory());
            ((javax.net.ssl.HttpsURLConnection) connection).setHostnameVerifier((host, session) -> host.equals("10.0.2.2"));
        }
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("Connection", "close");
        byte[] buffer = new byte[65536];
        CRC32 crc = new CRC32();
        long count = 0;
        long start = SystemClock.elapsedRealtime();
        try (InputStream input = connection.getInputStream()) {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() != size) throw new IllegalStateException("Bad response");
            // Stop at Content-Length. EOF/half-close idle timers must not enter a speed measurement.
            while (count < size) {
                int n = input.read(buffer, 0, (int) Math.min(buffer.length, size - count));
                if (n < 0) throw new IllegalStateException("Incomplete transfer: " + count);
                count += n;
                crc.update(buffer, 0, n);
            }
        } finally { connection.disconnect(); }
        long elapsed = SystemClock.elapsedRealtime() - start;
        System.out.println("BYTES=" + count + " MS=" + elapsed + " CRC=" + crc.getValue());
    }
}
