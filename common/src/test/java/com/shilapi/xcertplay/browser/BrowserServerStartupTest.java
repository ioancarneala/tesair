package com.shilapi.xcertplay.browser;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BrowserServerStartupTest {
    @Test public void acceptedBrowserSocketUsesTcpNoDelay() {
        BridgeServer server = new BridgeServer(RuntimeEnvironment.getApplication());
        boolean[] enabled = {false};
        Socket socket = new Socket() {
            @Override public void setTcpNoDelay(boolean on) { enabled[0] = on; }
        };
        assertNotNull(server.createClientHandler(socket, new ByteArrayInputStream(new byte[0])));
        assertTrue(enabled[0]);
    }

    @Test public void unsupportedTcpOptionDoesNotRejectTheBrowserConnection() {
        BridgeServer server = new BridgeServer(RuntimeEnvironment.getApplication());
        ArrayList<String> diagnostics = new ArrayList<>(); server.setDiagnosticHandler(diagnostics::add);
        Socket socket = new Socket() {
            @Override public void setTcpNoDelay(boolean on) throws SocketException {
                throw new SocketException("Unsupported socket option");
            }
        };
        assertNotNull(server.createClientHandler(socket, new ByteArrayInputStream(new byte[0])));
        assertEquals(java.util.Collections.singletonList("socket_low_delay_unavailable"), diagnostics);
    }

    @Test public void failedBindClosesItsSocketAndTheSameServerCanRetry() throws Exception {
        BridgeServer server = new BridgeServer(RuntimeEnvironment.getApplication());
        ServerSocket failed = new ServerSocket() {
            @Override public void bind(SocketAddress address) throws IOException {
                throw new BindException("Temporary bind failure");
            }
        };
        ServerSocket recovered = new ServerSocket() {
            @Override public void bind(SocketAddress address) throws IOException {
                super.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            }
        };
        AtomicInteger attempts = new AtomicInteger();
        server.setServerSocketFactory(() -> attempts.getAndIncrement() == 0 ? failed : recovered);
        try {
            assertThrows(BindException.class, () -> server.start(1000, true));
            assertTrue("A failed attempt must not leak an unbound socket", failed.isClosed());
            server.start(1000, true);
            assertTrue(server.isAlive());
            assertEquals(2, attempts.get());
            assertTrue(recovered.isBound());
            assertFalse(recovered.isClosed());
        } finally {
            server.stop();
            failed.close();
            recovered.close();
        }
        assertTrue(recovered.isClosed());
    }
}
