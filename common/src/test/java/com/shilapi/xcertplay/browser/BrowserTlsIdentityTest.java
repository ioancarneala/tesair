package com.shilapi.xcertplay.browser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import javax.security.auth.x500.X500Principal;
import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserTlsIdentityTest {
    private static final String HOST = TlsIdentity.TRUSTED_HOST;
    private static final long NOW = 1_790_000_000_000L;
    private static final Date BEFORE = new Date(NOW - 86_400_000L), AFTER = new Date(NOW + 86_400_000L);

    @Test public void csrSignatureAndPublicKeyAreIndependentlyVerifiable() throws Exception {
        KeyPair key = key();
        byte[] request = TlsIdentity.certificateRequest(key.getPrivate(), key.getPublic(), HOST);
        List<byte[]> outer = children(request), info = children(outer.get(0));
        assertEquals(3, outer.size()); assertEquals(4, info.size());
        assertArrayEquals(key.getPublic().getEncoded(), KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(info.get(2))).getEncoded());
        assertEquals(new X500Principal("CN=" + HOST), new X500Principal(info.get(1)));
        byte[] signature = body(outer.get(2)); assertEquals(0, signature[0]);
        Signature verifier = Signature.getInstance("SHA256withECDSA"); verifier.initVerify(key.getPublic());
        verifier.update(outer.get(0)); assertTrue(verifier.verify(Arrays.copyOfRange(signature, 1, signature.length)));
        List<byte[]> attribute = children(children(info.get(3)).get(0));
        assertArrayEquals(TlsIdentity.oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 1, 9, 14), attribute.get(0));
        byte[] extensions = children(attribute.get(1)).get(0);
        List<byte[]> san = children(children(extensions).get(0));
        assertArrayEquals(TlsIdentity.oid(0x55, 0x1d, 0x11), san.get(0));
        byte[] dnsName = children(body(san.get(1))).get(0);
        assertEquals(0x82, dnsName[0] & 255);
        assertEquals(HOST, new String(body(dnsName), StandardCharsets.US_ASCII));
        request[request.length - 1] ^= 1;
        verifier.initVerify(key.getPublic()); verifier.update(outer.get(0));
        byte[] altered = body(children(request).get(2));
        assertFalse(verifier.verify(Arrays.copyOfRange(altered, 1, altered.length)));
    }

    @Test public void csrRejectsAnUnapprovedHostname() throws Exception {
        KeyPair key = key();
        assertThrows(IllegalArgumentException.class,
                () -> TlsIdentity.certificateRequest(key.getPrivate(), key.getPublic(), "unrelated.example"));
        assertThrows(IllegalArgumentException.class,
                () -> TlsIdentity.certificateRequest(key.getPrivate(), key.getPublic(), "carplay.example.invalid"));
    }

    @Test public void pkixOrdersTheIntermediateChainAndOmitsTheRoot() throws Exception {
        Fixture f = new Fixture();
        X509Certificate[] result = TlsIdentity.validateChain(Arrays.asList(f.root, f.leaf, f.intermediate),
                f.device.getPublic(), HOST, f.anchors(), new Date(NOW));
        assertArrayEquals(new X509Certificate[]{f.leaf, f.intermediate}, result);
    }

    @Test public void rejectsWrongDeviceKeyAndUnknownRoot() throws Exception {
        Fixture f = new Fixture(); KeyPair other = key();
        assertThrows(CertificateException.class, () -> TlsIdentity.validateChain(f.chain(), other.getPublic(), HOST, f.anchors(), new Date(NOW)));
        Fixture unrelated = new Fixture();
        assertThrows(Exception.class, () -> TlsIdentity.validateChain(f.chain(), f.device.getPublic(), HOST, unrelated.anchors(), new Date(NOW)));
    }

    @Test public void hostnameMustBeAnExactDnsSanNotCommonNameOrWildcard() throws Exception {
        Fixture f = new Fixture();
        for (String san : new String[]{null, "*.tesair.co", "different.tesair.co", "carplay.example.invalid"}) {
            X509Certificate invalid = certificate(f.device, "CN=" + HOST, f.issuer, f.intermediate.getSubjectX500Principal(), false, san, 1, BEFORE, AFTER);
            assertThrows(CertificateException.class, () -> TlsIdentity.validateChain(Arrays.asList(invalid, f.intermediate),
                    f.device.getPublic(), HOST, f.anchors(), new Date(NOW)));
        }
    }

    @Test public void rejectsExpiredFutureAndClientOnlyCertificates() throws Exception {
        Fixture f = new Fixture();
        for (X509Certificate invalid : Arrays.asList(
                certificate(f.device, "CN=" + HOST, f.issuer, f.intermediate.getSubjectX500Principal(), false, HOST, 1,
                        new Date(NOW - 172800000L), new Date(NOW - 86400000L)),
                certificate(f.device, "CN=" + HOST, f.issuer, f.intermediate.getSubjectX500Principal(), false, HOST, 1,
                        new Date(NOW + 86400000L), new Date(NOW + 172800000L)),
                certificate(f.device, "CN=" + HOST, f.issuer, f.intermediate.getSubjectX500Principal(), false, HOST, 2, BEFORE, AFTER))) {
            assertThrows(CertificateException.class, () -> TlsIdentity.validateChain(Arrays.asList(invalid, f.intermediate),
                    f.device.getPublic(), HOST, f.anchors(), new Date(NOW)));
        }
    }

    @Test public void malformedTamperedAndMissingIntermediateChainsFail() throws Exception {
        Fixture f = new Fixture();
        assertThrows(Exception.class, () -> TlsIdentity.parseCertificates(new byte[]{1, 2, 3}));
        assertThrows(Exception.class, () -> TlsIdentity.parseCertificates(new byte[256 * 1024 + 1]));
        assertThrows(Exception.class, () -> TlsIdentity.validateChain(Collections.singletonList(f.leaf),
                f.device.getPublic(), HOST, f.anchors(), new Date(NOW)));
        byte[] tampered = f.leaf.getEncoded(); tampered[tampered.length - 1] ^= 1;
        X509Certificate altered = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(tampered));
        assertThrows(Exception.class, () -> TlsIdentity.validateChain(Arrays.asList(altered, f.intermediate),
                f.device.getPublic(), HOST, f.anchors(), new Date(NOW)));
    }

    @Test public void publicPemChainParsesWithoutPrivateMaterial() throws Exception {
        Fixture f = new Fixture(); StringBuilder pem = new StringBuilder();
        for (X509Certificate cert : f.chain()) pem.append("-----BEGIN CERTIFICATE-----\n")
                .append(Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(cert.getEncoded()))
                .append("\n-----END CERTIFICATE-----\n");
        assertEquals(f.chain(), TlsIdentity.parseCertificates(pem.toString().getBytes(StandardCharsets.US_ASCII)));
    }

    @Test public void diagnosticSnapshotContainsOnlyStablePublicCertificateFacts() throws Exception {
        Fixture f = new Fixture();
        X509Certificate[] chain = {f.leaf, f.intermediate};
        TlsIdentity.Diagnostics snapshot = TlsIdentity.diagnostics(chain, true, NOW);
        assertTrue(snapshot.available); assertTrue(snapshot.trusted); assertTrue(snapshot.hostnameMatch);
        assertEquals(BEFORE.getTime(), snapshot.notBeforeMillis);
        assertEquals(AFTER.getTime(), snapshot.notAfterMillis);
        assertEquals(2, snapshot.chainLength);
        assertEquals(TlsIdentity.Validity.CURRENT, snapshot.validity);
        chain[0] = f.root;
        assertTrue(snapshot.hostnameMatch);
        assertEquals(2, snapshot.chainLength);
        for (java.lang.reflect.Field field : TlsIdentity.Diagnostics.class.getDeclaredFields()) {
            assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()));
            assertTrue(field.getType().isPrimitive() || field.getType() == TlsIdentity.Validity.class);
        }
    }

    @Test public void diagnosticValidityDistinguishesClockExpiryHostnameAndTrust() throws Exception {
        Fixture f = new Fixture();
        X509Certificate[] chain = {f.leaf, f.intermediate};
        TlsIdentity.Diagnostics before = TlsIdentity.diagnostics(chain, true, BEFORE.getTime() - 1);
        assertEquals(TlsIdentity.Validity.CLOCK_BEFORE_VALIDITY, before.validity);
        assertFalse(before.trusted); assertTrue(before.hostnameMatch);
        TlsIdentity.Diagnostics expired = TlsIdentity.diagnostics(chain, true, AFTER.getTime() + 1);
        assertEquals(TlsIdentity.Validity.EXPIRED, expired.validity);
        assertFalse(expired.trusted);
        TlsIdentity.Diagnostics untrusted = TlsIdentity.diagnostics(chain, false, NOW);
        assertEquals(TlsIdentity.Validity.UNTRUSTED, untrusted.validity);
        assertFalse(untrusted.trusted); assertTrue(untrusted.hostnameMatch);
        X509Certificate wrongName = certificate(f.device, "CN=" + HOST, f.issuer,
                f.intermediate.getSubjectX500Principal(), false, "unrelated.example", 1, BEFORE, AFTER);
        TlsIdentity.Diagnostics mismatch = TlsIdentity.diagnostics(new X509Certificate[]{wrongName}, true, NOW);
        assertEquals(TlsIdentity.Validity.HOSTNAME_MISMATCH, mismatch.validity);
        assertFalse(mismatch.hostnameMatch); assertFalse(mismatch.trusted);
    }

    @Test public void missingCertificateHasAnExplicitEmptyDiagnosticSnapshot() {
        for (X509Certificate[] chain : new X509Certificate[][]{null, new X509Certificate[0]}) {
            TlsIdentity.Diagnostics snapshot = TlsIdentity.diagnostics(chain, true, NOW);
            assertFalse(snapshot.available); assertFalse(snapshot.trusted); assertFalse(snapshot.hostnameMatch);
            assertEquals(0, snapshot.chainLength);
            assertEquals(0, snapshot.notBeforeMillis); assertEquals(0, snapshot.notAfterMillis);
            assertEquals(TlsIdentity.Validity.MISSING, snapshot.validity);
        }
    }

    @Test public void failedInstallationRollsBackWithoutTouchingLocalFallback() throws Exception {
        Fixture f = new Fixture(); MemoryStore spi = new MemoryStore(); TestStore store = new TestStore(spi); store.load(null);
        X509Certificate old = certificate(f.device, "CN=Old certificate", f.issuer, f.intermediate.getSubjectX500Principal(), false, HOST, 1, BEFORE, AFTER);
        store.setKeyEntry("trusted", f.device.getPrivate(), null, new Certificate[]{old, f.intermediate});
        store.setKeyEntry("local", f.device.getPrivate(), null, new Certificate[]{f.root});
        spi.failAfterWrite = true;
        assertThrows(KeyStoreException.class, () -> TlsIdentity.replaceChain(store, "trusted", f.device.getPrivate(),
                new X509Certificate[]{f.leaf, f.intermediate}));
        assertArrayEquals(new Certificate[]{old, f.intermediate}, store.getCertificateChain("trusted"));
        assertArrayEquals(new Certificate[]{f.root}, store.getCertificateChain("local"));
        TlsIdentity.replaceChain(store, "trusted", f.device.getPrivate(), new X509Certificate[]{f.leaf, f.intermediate});
        assertArrayEquals(new Certificate[]{f.leaf, f.intermediate}, store.getCertificateChain("trusted"));
    }

    @Test public void sharedKeyRequiresUnencryptedPkcs8P256() throws Exception {
        KeyPair pair = key();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        assertArrayEquals(pair.getPrivate().getEncoded(), TlsIdentity.parseSharedPrivateKey(pem).getEncoded());
        assertThrows(Exception.class, () -> TlsIdentity.parseSharedPrivateKey(pem.replace("PRIVATE KEY", "EC PRIVATE KEY")));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp384r1"));
        String bigger = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        assertThrows(Exception.class, () -> TlsIdentity.parseSharedPrivateKey(bigger));
    }

    @Test public void sharedKeyCleanupOnlyRemovesStaleKeysForTheCurrentHostname() throws Exception {
        Fixture f = new Fixture(); MemoryStore spi = new MemoryStore(); TestStore store = new TestStore(spi); store.load(null);
        String legacyShared = "browser-shared-tls-5000-synthetic";
        String legacyManual = "browser-trusted-tls-ec-v1";
        String local = "browser-bridge-tls-ec-v1";
        String active = TlsIdentity.SHARED_PREFIX + "2-synthetic";
        String stale = TlsIdentity.SHARED_PREFIX + "1-synthetic";
        for (String alias : Arrays.asList(legacyShared, legacyManual, local, active, stale))
            store.setKeyEntry(alias, f.device.getPrivate(), null, new Certificate[]{f.leaf, f.intermediate});
        TlsIdentity.cleanupSharedKeys(store, active);
        for (String retained : Arrays.asList(legacyShared, legacyManual, local, active)) {
            assertTrue(retained, store.containsAlias(retained));
            assertArrayEquals(new Certificate[]{f.leaf, f.intermediate}, store.getCertificateChain(retained));
        }
        assertFalse(store.containsAlias(stale));
    }

    @Test public void sameVersionRepairsMissingKeysButNeverAcceptsAnOlderVersion() throws Exception {
        assertFalse(TlsIdentity.sharedUpdateRequired(7, 7, true));
        assertTrue(TlsIdentity.sharedUpdateRequired(7, 7, false));
        assertTrue(TlsIdentity.sharedUpdateRequired(8, 7, true));
        assertThrows(CertificateException.class, () -> TlsIdentity.sharedUpdateRequired(6, 7, false));
        assertThrows(CertificateException.class, () -> TlsIdentity.sharedUpdateRequired(0, 0, false));
    }

    @Test public void newerVersionAllowsShorterValidityButSameVersionRepairCannotReduceIt() throws Exception {
        TlsIdentity.validateSharedExpiry(8, 7, NOW + 86400000L, NOW + 30 * 86400000L);
        TlsIdentity.validateSharedExpiry(7, 7, AFTER.getTime(), AFTER.getTime());
        assertThrows(CertificateException.class,
                () -> TlsIdentity.validateSharedExpiry(7, 7, NOW + 86400000L, NOW + 30 * 86400000L));
    }

    @Test public void sharedCertificateRejectsAdditionalNamesAndShortValidity() throws Exception {
        Fixture f = new Fixture();
        assertArrayEquals(new X509Certificate[]{f.leaf, f.intermediate}, TlsIdentity.validateSharedChain(
                f.chain(), f.device.getPublic(), f.anchors(), new Date(NOW)));
        for (X509Certificate invalid : Arrays.asList(
                certificate(f.device, "CN=" + HOST, f.issuer, f.intermediate.getSubjectX500Principal(), false,
                        HOST + ",unrelated.example", 1, BEFORE, AFTER),
                certificate(f.device, "CN=" + HOST, f.issuer, f.intermediate.getSubjectX500Principal(), false,
                        HOST, 1, BEFORE, new Date(NOW + 3600000L)))) {
            assertThrows(Exception.class, () -> TlsIdentity.validateSharedChain(Arrays.asList(invalid, f.intermediate),
                    f.device.getPublic(), f.anchors(), new Date(NOW)));
        }
    }

    @Test public void liveRenewalKeepsEachHandshakeKeyAndChainTogether() throws Exception {
        Fixture old = new Fixture(), replacement = new Fixture();
        TlsIdentity.DynamicKeyManager manager = new TlsIdentity.DynamicKeyManager();
        manager.activate("old", old.device.getPrivate(), new X509Certificate[]{old.leaf, old.intermediate});
        java.net.Socket oldSocket = new java.net.Socket();
        String oldAlias = manager.chooseServerAlias("EC", null, oldSocket);
        manager.activate("new", replacement.device.getPrivate(), new X509Certificate[]{replacement.leaf, replacement.intermediate});
        String nextAlias = manager.chooseServerAlias("EC", null, new java.net.Socket());
        assertNotEquals(oldAlias, nextAlias);
        assertSame(old.device.getPrivate(), manager.getPrivateKey(oldAlias));
        assertEquals(old.leaf, manager.getCertificateChain(oldAlias)[0]);
        assertSame(replacement.device.getPrivate(), manager.getPrivateKey(nextAlias));
        assertEquals(replacement.leaf, manager.getCertificateChain(nextAlias)[0]);
        assertTrue(manager.retains("old"));
        oldSocket.close();
        assertFalse(manager.retains("old"));
        assertNull(manager.getPrivateKey(oldAlias));
    }

    private static final class Fixture {
        final KeyPair authority = key(), issuer = key(), device = key();
        final X509Certificate root = certificate(authority, "CN=Synthetic browser test root", authority,
                new X500Principal("CN=Synthetic browser test root"), true, null, 0, BEFORE, AFTER);
        final X509Certificate intermediate = certificate(issuer, "CN=Synthetic browser test intermediate", authority,
                root.getSubjectX500Principal(), true, null, 0, BEFORE, AFTER);
        final X509Certificate leaf = certificate(device, "CN=" + HOST, issuer,
                intermediate.getSubjectX500Principal(), false, HOST, 1, BEFORE, AFTER);
        Fixture() throws Exception { }
        List<X509Certificate> chain() { return Arrays.asList(leaf, intermediate); }
        Set<TrustAnchor> anchors() { return Collections.singleton(new TrustAnchor(root, null)); }
    }

    private static KeyPair key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(KeyPair subjectKey, String subject, KeyPair signingKey,
            X500Principal issuer, boolean ca, String san, int eku, Date before, Date after) throws Exception {
        List<byte[]> extensions = new ArrayList<>();
        extensions.add(TlsIdentity.der(0x30, TlsIdentity.oid(0x55, 0x1d, 0x13), TlsIdentity.der(1, new byte[]{(byte)255}),
                TlsIdentity.der(4, ca ? TlsIdentity.der(0x30, TlsIdentity.der(1, new byte[]{(byte)255})) : TlsIdentity.der(0x30))));
        extensions.add(TlsIdentity.der(0x30, TlsIdentity.oid(0x55, 0x1d, 0x0f), TlsIdentity.der(1, new byte[]{(byte)255}),
                TlsIdentity.der(4, TlsIdentity.der(3, new byte[]{(byte)(ca ? 1 : 7), (byte)(ca ? 6 : 128)}))));
        if (san != null) {
            List<byte[]> names = new ArrayList<>();
            for (String name : san.split(",")) names.add(TlsIdentity.der(0x82, name.getBytes(StandardCharsets.US_ASCII)));
            extensions.add(TlsIdentity.der(0x30, TlsIdentity.oid(0x55, 0x1d, 0x11),
                    TlsIdentity.der(4, TlsIdentity.der(0x30, names.toArray(new byte[0][])))));
        }
        if (eku != 0) extensions.add(TlsIdentity.der(0x30, TlsIdentity.oid(0x55, 0x1d, 0x25),
                TlsIdentity.der(4, TlsIdentity.der(0x30, TlsIdentity.oid(0x2b, 6, 1, 5, 5, 7, 3, eku)))));
        byte[] tbs = TlsIdentity.der(0x30, TlsIdentity.der(0xa0, TlsIdentity.der(2, new byte[]{2})),
                TlsIdentity.der(2, BigInteger.valueOf(Math.abs(subject.hashCode()) + 1L).toByteArray()), TlsIdentity.signatureAlgorithm(),
                issuer.getEncoded(), TlsIdentity.der(0x30, time(before), time(after)), new X500Principal(subject).getEncoded(),
                subjectKey.getPublic().getEncoded(), TlsIdentity.der(0xa3, TlsIdentity.der(0x30, extensions.toArray(new byte[0][]))));
        Signature signature = Signature.getInstance("SHA256withECDSA"); signature.initSign(signingKey.getPrivate()); signature.update(tbs);
        byte[] encoded = TlsIdentity.der(0x30, tbs, TlsIdentity.signatureAlgorithm(), TlsIdentity.der(3, new byte[]{0}, signature.sign()));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
    }
    private static byte[] time(Date date) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss'Z'", java.util.Locale.ROOT); formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return TlsIdentity.der(0x18, formatter.format(date).getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] body(byte[] encoded) { return Arrays.copyOfRange(encoded, header(encoded, 0), encoded.length); }
    private static int header(byte[] data, int offset) { int size = data[offset + 1] & 255; return offset + 2 + (size < 128 ? 0 : size & 127); }
    private static List<byte[]> children(byte[] encoded) {
        byte[] data = body(encoded); List<byte[]> result = new ArrayList<>();
        for (int offset = 0; offset < data.length;) {
            int start = header(data, offset), size = data[offset + 1] & 255;
            if (size >= 128) { int count = size & 127; size = 0; for (int i = 0; i < count; i++) size = (size << 8) | (data[offset + 2 + i] & 255); }
            result.add(Arrays.copyOfRange(data, offset, start + size)); offset = start + size;
        }
        return result;
    }

    private static final class TestStore extends KeyStore { TestStore(KeyStoreSpi spi) { super(spi, null, "Synthetic"); } }
    private static final class MemoryStore extends KeyStoreSpi {
        final Map<String, Certificate[]> chains = new LinkedHashMap<>(); final Map<String, Key> keys = new LinkedHashMap<>();
        boolean failAfterWrite;
        @Override public Key engineGetKey(String alias, char[] password) { return keys.get(alias); }
        @Override public Certificate[] engineGetCertificateChain(String alias) { Certificate[] chain = chains.get(alias); return chain == null ? null : chain.clone(); }
        @Override public Certificate engineGetCertificate(String alias) { Certificate[] chain = chains.get(alias); return chain == null ? null : chain[0]; }
        @Override public Date engineGetCreationDate(String alias) { return new Date(NOW); }
        @Override public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) throws KeyStoreException {
            chains.put(alias, chain.clone()); keys.put(alias, key);
            if (failAfterWrite) { failAfterWrite = false; throw new KeyStoreException("Synthetic post-write failure"); }
        }
        @Override public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) { throw new UnsupportedOperationException(); }
        @Override public void engineSetCertificateEntry(String alias, Certificate certificate) { chains.put(alias, new Certificate[]{certificate}); }
        @Override public void engineDeleteEntry(String alias) { chains.remove(alias); keys.remove(alias); }
        @Override public Enumeration<String> engineAliases() { return Collections.enumeration(chains.keySet()); }
        @Override public boolean engineContainsAlias(String alias) { return chains.containsKey(alias); }
        @Override public int engineSize() { return chains.size(); }
        @Override public boolean engineIsKeyEntry(String alias) { return keys.containsKey(alias); }
        @Override public boolean engineIsCertificateEntry(String alias) { return chains.containsKey(alias) && !keys.containsKey(alias); }
        @Override public String engineGetCertificateAlias(Certificate certificate) { return null; }
        @Override public void engineStore(OutputStream stream, char[] password) { }
        @Override public void engineLoad(InputStream stream, char[] password) { }
    }
}
