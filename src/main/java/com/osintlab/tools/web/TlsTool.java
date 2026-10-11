package com.osintlab.tools.web;

import com.osintlab.support.Inputs;
import com.osintlab.tools.OsintTool;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connects to host:443 and reports the certificate chain. Validation is done with the JDK trust
 * store plus hostname check; if that fails the chain is still fetched (trust-all) for inspection.
 */
@Component
public class TlsTool implements OsintTool {

    private static final int TIMEOUT_MS = 8000;

    private record Handshake(String protocol, String cipher, Certificate[] chain) {
    }

    @Override
    public String id() {
        return "tls";
    }

    @Override
    public Object run(String target) throws Exception {
        String raw = Inputs.require(target, "host");
        String host;
        int port = 443;
        if (raw.matches("(?i)^https?://.*")) {
            URI uri = Inputs.url(raw);
            host = uri.getHost();
            if (uri.getPort() > 0) port = uri.getPort();
        } else {
            host = Inputs.host(raw);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host", host);
        out.put("port", port);

        Handshake hs;
        try {
            hs = handshake(host, port, (SSLSocketFactory) SSLSocketFactory.getDefault(), true);
            out.put("trusted", true);
        } catch (SSLException e) {
            out.put("trusted", false);
            out.put("trustError", e.getMessage());
            hs = handshake(host, port, trustAll(), false);
        }
        out.put("protocol", hs.protocol());
        out.put("cipherSuite", hs.cipher());

        List<Map<String, Object>> chain = new ArrayList<>();
        for (Certificate c : hs.chain()) {
            if (c instanceof X509Certificate x) chain.add(describe(x));
        }
        out.put("chain", chain);
        return out;
    }

    private static Handshake handshake(String host, int port, SSLSocketFactory factory, boolean verifyHostname)
            throws Exception {
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            raw.setSoTimeout(TIMEOUT_MS);
            try (SSLSocket s = (SSLSocket) factory.createSocket(raw, host, port, true)) {
                if (verifyHostname) {
                    SSLParameters p = s.getSSLParameters();
                    p.setEndpointIdentificationAlgorithm("HTTPS");
                    s.setSSLParameters(p);
                }
                s.startHandshake();
                return new Handshake(s.getSession().getProtocol(), s.getSession().getCipherSuite(),
                        s.getSession().getPeerCertificates());
            }
        }
    }

    private static Map<String, Object> describe(X509Certificate c) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", c.getSubjectX500Principal().getName(X500Principal.RFC2253));
        m.put("issuer", c.getIssuerX500Principal().getName(X500Principal.RFC2253));
        m.put("validFrom", c.getNotBefore().toInstant().toString());
        m.put("validUntil", c.getNotAfter().toInstant().toString());
        m.put("daysLeft", Duration.between(Instant.now(), c.getNotAfter().toInstant()).toDays());
        m.put("serial", c.getSerialNumber().toString(16));
        m.put("signature", c.getSigAlgName());
        m.put("key", keyDescription(c));

        List<String> sans = new ArrayList<>();
        Collection<List<?>> alt = c.getSubjectAlternativeNames();
        if (alt != null) {
            for (List<?> entry : alt) {
                int type = (Integer) entry.get(0);
                if (type == 2 || type == 7) sans.add(String.valueOf(entry.get(1))); // DNS, IP
            }
        }
        if (!sans.isEmpty()) m.put("altNames", sans);

        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
        m.put("sha256", sha256);
        m.put("crtSh", "https://crt.sh/?q=" + sha256);
        return m;
    }

    private static String keyDescription(X509Certificate c) {
        var key = c.getPublicKey();
        if (key instanceof RSAPublicKey rsa) return "RSA " + rsa.getModulus().bitLength();
        if (key instanceof ECPublicKey ec) return "EC " + ec.getParams().getOrder().bitLength();
        return key.getAlgorithm();
    }

    /** Only used to read the chain of servers whose certificate failed validation. */
    private static SSLSocketFactory trustAll() throws Exception {
        TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, null);
        return ctx.getSocketFactory();
    }
}
