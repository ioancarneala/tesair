package com.shilapi.xcertplay.browser;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.content.Context;
import android.content.SharedPreferences;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Collections;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/** Local keys are generated in AndroidKeyStore; authorized shared bundles import a dedicated stream key there. */
public final class TlsIdentity {
    public static final String TRUSTED_HOST = "carplay.tesair.co";
    private static final String LOCAL_ALIAS = "browser-bridge-tls-ec-v1";
    private static final String TRUSTED_ALIAS = "browser-trusted-tls-" + TRUSTED_HOST + "-ec-v1";
    private static final int MAX_CHAIN_BYTES = 256 * 1024;
    private static volatile X509Certificate activeServerCertificate;
    private static volatile X509Certificate trustedCertificate;
    private static volatile boolean trustChecked;
    // Domain migrations have independent rollback floors and key cleanup. Legacy identities stay
    // untouched; a failed enrollment must never delete a previously installed domain's keys.
    static final String SHARED_PREFERENCES = "browser_shared_identity_" + TRUSTED_HOST;
    static final String SHARED_PREFIX = "browser-stream-tls-" + TRUSTED_HOST + "-";
    private static final int MAX_SHARED_KEYS = 3;
    private static Context sharedContext;
    private static final DynamicKeyManager LIVE_KEYS = new DynamicKeyManager();

    /** Call before server startup; stores only the application context, never a UI context. */
    public static synchronized void initializeSharedStorage(Context context) {
        sharedContext = context.getApplicationContext();
    }

    private static SharedPreferences sharedPreferences() {
        if (sharedContext == null) throw new IllegalStateException("Shared TLS storage is not initialized");
        return sharedContext.getSharedPreferences(SHARED_PREFERENCES, Context.MODE_PRIVATE);
    }

    public static synchronized long sharedVersion() { return sharedContext == null ? 0 : sharedPreferences().getLong("version", 0); }
    public static synchronized long sharedNotAfter() { return sharedContext == null ? 0 : sharedPreferences().getLong("not_after", 0); }
    public static synchronized String trustedHostname() { return hasTrustedCertificate() ? TRUSTED_HOST : null; }


    private TlsIdentity() { }

    static synchronized SSLServerSocketFactory create() throws Exception {
        KeyStore store = androidStore();
        ensureKey(store, LOCAL_ALIAS, "DiPlay Browser Bridge");
        X509Certificate[] trusted = sharedChain(store);
        String alias = trusted == null ? TRUSTED_ALIAS : sharedPreferences().getString("alias", "");
        if (trusted == null) trusted = trustedChain(store);
        if (trusted == null) alias = LOCAL_ALIAS;
        X509Certificate[] chain = trusted == null ? certificates(store.getCertificateChain(alias)) : trusted;
        PrivateKey key = (PrivateKey) store.getKey(alias, null);
        SSLContext context = SSLContext.getInstance("TLS");
        LIVE_KEYS.activate(alias, key, chain);
        context.init(new KeyManager[]{LIVE_KEYS}, null, null);
        activeServerCertificate = chain[0];
        return context.getServerSocketFactory();
    }

    /** Worker-thread operation. The destination contains no private key or account credential. */
    public static synchronized void writeCertificateRequest(File destination) throws Exception {
        KeyStore store = androidStore();
        ensureKey(store, TRUSTED_ALIAS, TRUSTED_HOST);
        byte[] csr = certificateRequest((PrivateKey) store.getKey(TRUSTED_ALIAS, null),
                store.getCertificate(TRUSTED_ALIAS).getPublicKey(), TRUSTED_HOST);
        byte[] pem = ("-----BEGIN CERTIFICATE REQUEST-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(csr)
                + "\n-----END CERTIFICATE REQUEST-----\n").getBytes(StandardCharsets.US_ASCII);
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) throw new java.io.IOException("CSR directory unavailable");
        File temporary = File.createTempFile("browser-csr-", ".tmp", parent);
        try {
            Files.write(temporary.toPath(), pem);
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    /** Installs only a valid system-trusted chain for this phone's CSR. Restart HTTPS to activate it. */
    public static synchronized void installCertificateChain(File pemFile) throws Exception {
        KeyStore store = androidStore();
        if (!store.containsAlias(TRUSTED_ALIAS)) throw new CertificateException("Generate this phone's CSR first");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(pemFile.toPath())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] block = new byte[4096]; int count;
            while ((count = input.read(block)) != -1) {
                if (out.size() + count > MAX_CHAIN_BYTES) throw new CertificateException("Certificate file too large");
                out.write(block, 0, count);
            }
            bytes = out.toByteArray();
        }
        List<X509Certificate> supplied = parseCertificates(bytes);
        X509Certificate[] chain = validateChain(supplied, store.getCertificate(TRUSTED_ALIAS).getPublicKey(),
                TRUSTED_HOST, systemTrustAnchors(), new Date());
        PrivateKey key = (PrivateKey) store.getKey(TRUSTED_ALIAS, null);
        verifyKeyPair(key, chain[0].getPublicKey());
        replaceChain(store, TRUSTED_ALIAS, key, chain);
        trustedCertificate = chain[0]; trustChecked = true;
        if (sharedChain(store) == null) {
            LIVE_KEYS.activate(TRUSTED_ALIAS, key, chain);
            activeServerCertificate = chain[0];
        }
    }

    /** Cached after server startup/import; first call must be made on a worker. */
    public static synchronized boolean hasTrustedCertificate() {
        X509Certificate cached = trustedCertificate;
        boolean expired = false;
        if (cached != null) try { cached.checkValidity(); } catch (CertificateException invalid) { expired = true; }
        if (!trustChecked || expired) {
            try {
                KeyStore store = androidStore();
                X509Certificate[] chain = sharedChain(store);
                String alias = chain == null ? TRUSTED_ALIAS : sharedPreferences().getString("alias", "");
                if (chain == null) chain = trustedChain(store);
                if (chain != null) {
                    LIVE_KEYS.activate(alias, (PrivateKey) store.getKey(alias, null), chain);
                    activeServerCertificate = chain[0];
                }
            } catch (Exception ignored) { trustChecked = true; }
        }
        X509Certificate certificate = trustedCertificate;
        if (certificate == null) return false;
        try { certificate.checkValidity(); return true; } catch (CertificateException invalid) { return false; }
    }

    /** Expiry for renewal scheduling, or zero when no validated certificate has been loaded. */
    public static synchronized long trustedCertificateNotAfter() {
        hasTrustedCertificate();
        return trustedCertificate == null ? 0 : trustedCertificate.getNotAfter().getTime();
    }

    /** Validates the entire bundle before writing; the old live identity survives every failure. */
    public static synchronized boolean installSharedBundle(long version, String hostname,
            String chainPem, String keyPem) throws Exception {
        if (version <= 0 || !TRUSTED_HOST.equals(hostname)) throw new CertificateException("Invalid shared identity");
        SharedPreferences preferences = sharedPreferences();
        long previousVersion = preferences.getLong("version", 0);
        if (!sharedUpdateRequired(version, previousVersion,
                version == previousVersion && sharedChain(androidStore()) != null)) return false;
        PrivateKey imported = parseSharedPrivateKey(keyPem);
        List<X509Certificate> supplied = parseCertificates(chainPem.getBytes(StandardCharsets.US_ASCII));
        X509Certificate leaf = null;
        for (X509Certificate candidate : supplied) {
            try { verifyKeyPair(imported, candidate.getPublicKey()); leaf = candidate; break; }
            catch (Exception mismatch) { /* The matching leaf is selected before PKIX validation. */ }
        }
        if (leaf == null) throw new CertificateException("Shared key does not match certificate");
        X509Certificate[] chain = validateSharedChain(supplied, leaf.getPublicKey(), systemTrustAnchors(), new Date());
        validateSharedExpiry(version, previousVersion, chain[0].getNotAfter().getTime(),
                preferences.getLong("not_after", 0));
        KeyStore store = androidStore();
        String activeAlias = preferences.getString("alias", "");
        // A repair gets a separate key alias too: a vendor may retain an invalid key object in an old handshake.
        String alias = SHARED_PREFIX + version + "-" + java.util.UUID.randomUUID();
        cleanupSharedKeys(store, activeAlias);
        int retained = 0;
        for (String name : Collections.list(store.aliases())) if (name.startsWith(SHARED_PREFIX)) retained++;
        if (retained >= MAX_SHARED_KEYS) throw new CertificateException("Previous TLS handshakes are still finishing");
        boolean committed = false;
        try {
            store.setEntry(alias, new KeyStore.PrivateKeyEntry(imported, chain),
                    new KeyProtection.Builder(KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512).build());
            PrivateKey installed = (PrivateKey) store.getKey(alias, null);
            X509Certificate[] installedChain = certificates(store.getCertificateChain(alias));
            if (!Arrays.equals(chain[0].getEncoded(), installedChain[0].getEncoded()))
                throw new CertificateException("Shared identity installation incomplete");
            verifyKeyPair(installed, chain[0].getPublicKey());
            // The committed alias is the sole durable pointer. Orphan keys from a process crash are ignored.
            persistSharedMetadata(preferences, alias, version, chain[0].getNotAfter().getTime());
            committed = true;
            LIVE_KEYS.activate(alias, installed, chain);
            activeServerCertificate = chain[0]; trustedCertificate = chain[0]; trustChecked = true;
        } catch (Exception failure) {
            if (!committed) {
                try { store.deleteEntry(alias); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        try { cleanupSharedKeys(store, alias); } catch (Exception ignored) { /* Retry cleanup on the next renewal. */ }
        return true;
    }

    static void persistSharedMetadata(SharedPreferences preferences, String alias, long version, long expiry) throws java.io.IOException {
        String previousAlias = preferences.getString("alias", "");
        long previousVersion = preferences.getLong("version", 0), previousExpiry = preferences.getLong("not_after", 0);
        if (preferences.edit().putString("alias", alias).putLong("version", version).putLong("not_after", expiry).commit()) return;
        // Android may update the in-memory map even when disk commit fails. Restore both before returning failure.
        preferences.edit().putString("alias", previousAlias).putLong("version", previousVersion)
                .putLong("not_after", previousExpiry).commit();
        throw new java.io.IOException("Shared identity metadata could not be saved");
    }

    static boolean sharedUpdateRequired(long version, long installed, boolean available) throws CertificateException {
        if (version <= 0 || version < installed) throw new CertificateException("Shared certificate version is older than the installed identity");
        return version > installed || !available;
    }

    static void validateSharedExpiry(long version, long installed, long expiry, long installedExpiry) throws CertificateException {
        // Newer versions can rotate a compromised key or adopt a CA's shorter certificate lifetime.
        if (version == installed && expiry < installedExpiry)
            throw new CertificateException("Shared certificate expiry moved backwards");
    }

    static PrivateKey parseSharedPrivateKey(String pem) throws Exception {
        if (pem == null || pem.length() > 16384) throw new CertificateException("Invalid shared key");
        String begin = "-----BEGIN PRIVATE KEY-----", end = "-----END PRIVATE KEY-----";
        String trimmed = pem.trim();
        if (!trimmed.startsWith(begin) || !trimmed.endsWith(end)) throw new CertificateException("PKCS8 key required");
        String body = trimmed.substring(begin.length(), trimmed.length() - end.length()).replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        if (!(key instanceof ECPrivateKey) || !isP256(((ECPrivateKey) key).getParams()))
            throw new CertificateException("P-256 key required");
        return key;
    }

    private static boolean isP256(java.security.spec.ECParameterSpec actual) throws Exception {
        java.security.AlgorithmParameters parameters = java.security.AlgorithmParameters.getInstance("EC");
        parameters.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.spec.ECParameterSpec expected = parameters.getParameterSpec(java.security.spec.ECParameterSpec.class);
        return expected.getCurve().equals(actual.getCurve()) && expected.getGenerator().equals(actual.getGenerator())
                && expected.getOrder().equals(actual.getOrder()) && expected.getCofactor() == actual.getCofactor();
    }

    static X509Certificate[] validateSharedChain(List<X509Certificate> supplied, PublicKey key,
            Set<TrustAnchor> anchors, Date now) throws Exception {
        X509Certificate[] chain = validateChain(supplied, key, TRUSTED_HOST, anchors, now);
        Collection<List<?>> names = chain[0].getSubjectAlternativeNames();
        if (names == null || names.size() != 1) throw new CertificateException("Dedicated hostname certificate required");
        if (!(key instanceof ECPublicKey) || !isP256(((ECPublicKey) key).getParams()))
            throw new CertificateException("P-256 certificate required");
        if (chain[0].getNotAfter().getTime() - now.getTime() < 86400000L)
            throw new CertificateException("Shared certificate expires too soon");
        return chain;
    }

    private static X509Certificate[] sharedChain(KeyStore store) {
        if (sharedContext == null) return null;
        String alias = sharedPreferences().getString("alias", "");
        if (!alias.startsWith(SHARED_PREFIX)) return null;
        try {
            X509Certificate[] chain = certificates(store.getCertificateChain(alias));
            X509Certificate[] trusted = validateChain(Arrays.asList(chain), chain[0].getPublicKey(),
                    TRUSTED_HOST, systemTrustAnchors(), new Date());
            verifyKeyPair((PrivateKey) store.getKey(alias, null), trusted[0].getPublicKey());
            trustedCertificate = trusted[0]; trustChecked = true;
            return trusted;
        } catch (Exception invalid) { return null; }
    }

    static void cleanupSharedKeys(KeyStore store, String activeAlias) throws Exception {
        for (String alias : Collections.list(store.aliases())) {
            if (alias.startsWith(SHARED_PREFIX) && !alias.equals(activeAlias) && !LIVE_KEYS.retains(alias))
                store.deleteEntry(alias);
        }
    }

    private static KeyStore androidStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
    }

    private static void ensureKey(KeyStore store, String alias, String subject) throws Exception {
        if (store.containsAlias(alias)) return;
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                // Conscrypt signs a prehashed TLS transcript through NONEwithECDSA.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(new X500Principal("CN=" + subject))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(new Date(System.currentTimeMillis() - 86400000L))
                .setCertificateNotAfter(new Date(System.currentTimeMillis() + 3650L * 86400000L)).build());
        generator.generateKeyPair();
    }

    private static X509Certificate[] trustedChain(KeyStore store) {
        trustedCertificate = null; trustChecked = true;
        try {
            Certificate[] existing = store.getCertificateChain(TRUSTED_ALIAS);
            if (existing == null) return null;
            X509Certificate[] chain = validateChain(Arrays.asList(certificates(existing)), existing[0].getPublicKey(),
                    TRUSTED_HOST, systemTrustAnchors(), new Date());
            verifyKeyPair((PrivateKey) store.getKey(TRUSTED_ALIAS, null), chain[0].getPublicKey());
            trustedCertificate = chain[0]; return chain;
        } catch (Exception invalidOrUnavailable) { return null; }
    }

    static List<X509Certificate> parseCertificates(byte[] bytes) throws Exception {
        if (bytes.length == 0 || bytes.length > MAX_CHAIN_BYTES) throw new CertificateException("Invalid certificate file size");
        Collection<? extends Certificate> parsed = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(bytes));
        if (parsed.isEmpty() || parsed.size() > 10) throw new CertificateException("Invalid certificate chain length");
        List<X509Certificate> result = new ArrayList<>();
        for (Certificate certificate : parsed) result.add((X509Certificate) certificate);
        return result;
    }

    static X509Certificate[] validateChain(List<X509Certificate> supplied, PublicKey expectedKey, String host,
            Set<TrustAnchor> anchors, Date now) throws Exception {
        if (supplied.isEmpty() || supplied.size() > 10) throw new CertificateException("Invalid certificate chain length");
        X509Certificate leaf = null;
        for (X509Certificate candidate : supplied) {
            if (MessageDigest.isEqual(candidate.getPublicKey().getEncoded(), expectedKey.getEncoded())) {
                if (leaf != null) throw new CertificateException("Ambiguous certificate for this key");
                leaf = candidate;
            }
        }
        if (leaf == null) throw new CertificateException("Certificate does not match this phone's key");
        leaf.checkValidity(now);
        if (leaf.getBasicConstraints() >= 0) throw new CertificateException("A server certificate is required");
        boolean hostMatches = false;
        Collection<List<?>> names = leaf.getSubjectAlternativeNames();
        if (names != null) for (List<?> name : names) {
            if (name.size() >= 2 && Integer.valueOf(2).equals(name.get(0)) && host.equalsIgnoreCase(String.valueOf(name.get(1))))
                hostMatches = true;
        }
        if (!hostMatches) throw new CertificateException("Certificate SAN must contain " + host);
        List<String> usage = leaf.getExtendedKeyUsage();
        if (usage != null && !usage.contains("1.3.6.1.5.5.7.3.1")) throw new CertificateException("Certificate cannot serve HTTPS");
        boolean[] keyUsage = leaf.getKeyUsage();
        if (keyUsage != null && (keyUsage.length == 0 || !keyUsage[0])) throw new CertificateException("TLS signing is not permitted");
        X509CertSelector selector = new X509CertSelector(); selector.setCertificate(leaf);
        PKIXBuilderParameters params = new PKIXBuilderParameters(anchors, selector);
        params.setDate(now); params.setRevocationEnabled(false);
        params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(supplied)));
        PKIXCertPathBuilderResult result = (PKIXCertPathBuilderResult) CertPathBuilder.getInstance("PKIX").build(params);
        List<X509Certificate> ordered = new ArrayList<>();
        for (Certificate certificate : result.getCertPath().getCertificates()) {
            ((X509Certificate) certificate).checkValidity(now); ordered.add((X509Certificate) certificate);
        }
        if (ordered.isEmpty()) throw new CertificateException("Server certificate must not be a trust anchor");
        // Send the intermediate chain; the browser already holds the root trust anchor.
        return ordered.toArray(new X509Certificate[0]);
    }

    static void replaceChain(KeyStore store, String alias, PrivateKey key, X509Certificate[] chain) throws Exception {
        Certificate[] previous = store.getCertificateChain(alias);
        try {
            store.setKeyEntry(alias, key, null, chain);
            Certificate[] installed = store.getCertificateChain(alias);
            if (installed == null || installed.length != chain.length) throw new CertificateException("Certificate installation incomplete");
            for (int i = 0; i < chain.length; i++) {
                if (!Arrays.equals(chain[i].getEncoded(), installed[i].getEncoded()))
                    throw new CertificateException("Certificate installation could not be verified");
            }
        } catch (Exception failure) {
            if (previous != null) try { store.setKeyEntry(alias, key, null, previous); }
            catch (Exception rollback) { failure.addSuppressed(rollback); }
            // The independent LOCAL_ALIAS is never touched, even if vendor keystore rollback fails.
            throw failure;
        }
    }

    private static Set<TrustAnchor> systemTrustAnchors() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        Set<TrustAnchor> anchors = new HashSet<>();
        for (TrustManager manager : factory.getTrustManagers()) if (manager instanceof X509TrustManager) {
            for (X509Certificate root : ((X509TrustManager) manager).getAcceptedIssuers()) anchors.add(new TrustAnchor(root, null));
        }
        return anchors;
    }

    private static void verifyKeyPair(PrivateKey key, PublicKey publicKey) throws Exception {
        byte[] proof = "DiPlay TLS key ownership".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("SHA256withECDSA"); signer.initSign(key); signer.update(proof);
        byte[] signature = signer.sign(); signer.initVerify(publicKey); signer.update(proof);
        if (!signer.verify(signature)) throw new CertificateException("Certificate key ownership could not be verified");
    }

    static byte[] certificateRequest(PrivateKey key, PublicKey publicKey, String host) throws Exception {
        if (!TRUSTED_HOST.equals(host)) throw new IllegalArgumentException("Unsupported certificate hostname");
        byte[] san = der(0x30, der(0x82, host.getBytes(StandardCharsets.US_ASCII)));
        byte[] extensions = der(0x30, der(0x30, oid(0x55, 0x1d, 0x11), der(0x04, san)));
        byte[] attribute = der(0x30, oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 1, 9, 14), der(0x31, extensions));
        byte[] request = der(0x30, der(0x02, new byte[]{0}), new X500Principal("CN=" + host).getEncoded(),
                publicKey.getEncoded(), der(0xa0, attribute));
        Signature signer = Signature.getInstance("SHA256withECDSA"); signer.initSign(key); signer.update(request);
        return der(0x30, request, signatureAlgorithm(), der(0x03, new byte[]{0}, signer.sign()));
    }

    static byte[] signatureAlgorithm() { return der(0x30, oid(0x2a, 0x86, 0x48, 0xce, 0x3d, 4, 3, 2)); }
    static byte[] oid(int... values) {
        byte[] bytes = new byte[values.length]; for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return der(0x06, bytes);
    }
    static byte[] der(int tag, byte[]... values) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] value : values) content.write(value, 0, value.length);
        byte[] bytes = content.toByteArray(); ByteArrayOutputStream result = new ByteArrayOutputStream(); result.write(tag);
        if (bytes.length < 128) result.write(bytes.length);
        else {
            int size = 0; for (int length = bytes.length; length > 0; length >>>= 8) size++;
            result.write(0x80 | size);
            for (int index = size - 1; index >= 0; index--) result.write(bytes.length >>> (8 * index));
        }
        result.write(bytes, 0, bytes.length); return result.toByteArray();
    }

    private static X509Certificate[] certificates(Certificate[] source) throws CertificateException {
        if (source == null || source.length == 0) throw new CertificateException("Certificate missing");
        X509Certificate[] result = new X509Certificate[source.length];
        for (int i = 0; i < source.length; i++) result[i] = (X509Certificate) source[i];
        return result;
    }

    /** Each handshake receives an immutable alias; renewal cannot mix an old key with a new chain. */
    static final class DynamicKeyManager extends X509ExtendedKeyManager {
        private static final class Identity {
            final String storageAlias, id; final PrivateKey key; final X509Certificate[] chain;
            Identity(String alias, PrivateKey key, X509Certificate[] chain) throws Exception {
                this.storageAlias = alias; this.key = key; this.chain = chain.clone();
                this.id = alias + ":" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
            }
        }
        private final Map<String, Identity> snapshots = new LinkedHashMap<>();
        private final Map<Socket, String> sockets = new WeakHashMap<>();
        private final Map<SSLEngine, String> engines = new WeakHashMap<>();
        private Identity current;
        synchronized void activate(String alias, PrivateKey key, X509Certificate[] chain) throws Exception {
            Identity identity = new Identity(alias, key, chain);
            snapshots.put(identity.id, identity); current = identity; prune();
        }
        synchronized boolean retains(String alias) {
            prune();
            for (Identity identity : snapshots.values()) if (identity.storageAlias.equals(alias)) return true;
            return false;
        }
        private void prune() {
            sockets.keySet().removeIf(Socket::isClosed);
            engines.keySet().removeIf(engine -> engine.isOutboundDone() || engine.isInboundDone()
                    || engine.getHandshakeStatus() == javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING);
            snapshots.entrySet().removeIf(entry -> (current == null || !entry.getKey().equals(current.id))
                    && !sockets.containsValue(entry.getKey()) && !engines.containsValue(entry.getKey()));
        }
        @Override public synchronized String chooseServerAlias(String type, Principal[] issuers, Socket socket) {
            if (!accepts(type) || current == null) return null;
            String id = current.id;
            if (socket != null) {
                String previous = sockets.putIfAbsent(socket, id);
                if (previous != null) return previous;
                if (socket instanceof SSLSocket) ((SSLSocket) socket).addHandshakeCompletedListener(event -> {
                    synchronized (DynamicKeyManager.this) { sockets.remove(socket); prune(); }
                });
            }
            return id;
        }
        @Override public synchronized String chooseEngineServerAlias(String type, Principal[] issuers, SSLEngine engine) {
            if (!accepts(type) || current == null) return null;
            return engines.computeIfAbsent(engine, unused -> current.id);
        }
        @Override public synchronized String[] getServerAliases(String type, Principal[] issuers) {
            return accepts(type) && current != null ? new String[]{current.id} : null;
        }
        @Override public synchronized X509Certificate[] getCertificateChain(String alias) {
            Identity identity = snapshots.get(alias); return identity == null ? null : identity.chain.clone();
        }
        @Override public synchronized PrivateKey getPrivateKey(String alias) {
            Identity identity = snapshots.get(alias); return identity == null ? null : identity.key;
        }
        @Override public String[] getClientAliases(String type, Principal[] issuers) { return null; }
        @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) { return null; }
        private boolean accepts(String type) { return "EC".equals(type) || (type != null && type.startsWith("EC_")); }
    }

    static void checkLocalServer() throws Exception {
        X509Certificate certificate = activeServerCertificate;
        if (certificate == null) throw new CertificateException("Active server certificate missing");
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
        trust.setCertificateEntry("this-phone", certificate);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(trust);
        SSLContext client = SSLContext.getInstance("TLS"); client.init(null, factory.getTrustManagers(), null);
        try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) client.getSocketFactory().createSocket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", BridgeServer.PORT), 2000);
            socket.setSoTimeout(2000); socket.startHandshake();
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + BridgeServer.PORT
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String line = reader.readLine();
            if (line == null || !line.startsWith("HTTP/1.1 200 ")) throw new java.io.IOException("Player request failed");
        }
    }
}
