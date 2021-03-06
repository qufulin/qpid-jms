/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.jms.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import org.apache.qpid.jms.JmsConnectionFactory;
import org.apache.qpid.jms.test.QpidJmsTestCase;
import org.apache.qpid.jms.test.testpeer.TestAmqpPeer;
import org.apache.qpid.jms.transports.TransportSslOptions;
import org.apache.qpid.jms.transports.TransportSupport;
import org.junit.Test;

public class SslIntegrationTest extends QpidJmsTestCase {

    private static final String BROKER_JKS_KEYSTORE = "src/test/resources/broker-jks.keystore";
    private static final String BROKER_JKS_TRUSTSTORE = "src/test/resources/broker-jks.truststore";
    private static final String CLIENT_MULTI_KEYSTORE = "src/test/resources/client-multiple-keys-jks.keystore";
    private static final String CLIENT_JKS_TRUSTSTORE = "src/test/resources/client-jks.truststore";
    private static final String CLIENT_JKS_KEYSTORE = "src/test/resources/client-jks.keystore";
    private static final String CLIENT2_JKS_KEYSTORE = "src/test/resources/client2-jks.keystore";
    private static final String PASSWORD = "password";

    private static final String CLIENT_KEY_ALIAS = "client";
    private static final String CLIENT_DN = "O=Client,CN=client";
    private static final String CLIENT2_KEY_ALIAS = "client2";
    private static final String CLIENT2_DN = "O=Client2,CN=client2";

    private static final String ALIAS_DOES_NOT_EXIST = "alias.does.not.exist";
    private static final String ALIAS_CA_CERT = "ca";

    private final IntegrationTestFixture testFixture = new IntegrationTestFixture();

    @Test(timeout = 20000)
    public void testCreateAndCloseSslConnection() throws Exception {
        TransportSslOptions sslOptions = new TransportSslOptions();
        sslOptions.setKeyStoreLocation(BROKER_JKS_KEYSTORE);
        sslOptions.setKeyStorePassword(PASSWORD);
        sslOptions.setVerifyHost(false);

        SSLContext context = TransportSupport.createSslContext(sslOptions);

        try (TestAmqpPeer testPeer = new TestAmqpPeer(context, false);) {
            String connOptions = "?transport.trustStoreLocation=" + CLIENT_JKS_TRUSTSTORE + "&" +
                                 "transport.trustStorePassword=" + PASSWORD;
            Connection connection = testFixture.establishConnecton(testPeer, true, connOptions, null, null, true);

            Socket socket = testPeer.getClientSocket();
            assertTrue(socket instanceof SSLSocket);

            testPeer.expectClose();
            connection.close();
        }
    }

    @Test(timeout = 20000)
    public void testCreateSslConnectionWithServerSendingPreemptiveData() throws Exception {
        TransportSslOptions serverSslOptions = new TransportSslOptions();
        serverSslOptions.setKeyStoreLocation(BROKER_JKS_KEYSTORE);
        serverSslOptions.setKeyStorePassword(PASSWORD);
        serverSslOptions.setVerifyHost(false);

        SSLContext serverSslContext = TransportSupport.createSslContext(serverSslOptions);

        boolean sendServerSaslHeaderPreEmptively = true;
        try (TestAmqpPeer testPeer = new TestAmqpPeer(serverSslContext, false, sendServerSaslHeaderPreEmptively);) {
            // Don't use test fixture, handle the connection directly to control sasl behaviour
            testPeer.expectSaslAnonymousWithPreEmptiveServerHeader();
            testPeer.expectOpen();
            testPeer.expectBegin();

            String connOptions = "?transport.trustStoreLocation=" + CLIENT_JKS_TRUSTSTORE + "&" +
                                  "transport.trustStorePassword=" + PASSWORD;

            JmsConnectionFactory factory = new JmsConnectionFactory("amqps://localhost:" + testPeer.getServerPort() + connOptions);
            Connection connection = factory.createConnection();
            connection.start();

            Socket socket = testPeer.getClientSocket();
            assertTrue(socket instanceof SSLSocket);

            testPeer.expectClose();
            connection.close();
        }
    }

    @Test(timeout = 20000)
    public void testCreateAndCloseSslConnectionWithClientAuth() throws Exception {
        TransportSslOptions sslOptions = new TransportSslOptions();
        sslOptions.setKeyStoreLocation(BROKER_JKS_KEYSTORE);
        sslOptions.setTrustStoreLocation(BROKER_JKS_TRUSTSTORE);
        sslOptions.setKeyStorePassword(PASSWORD);
        sslOptions.setTrustStorePassword(PASSWORD);
        sslOptions.setVerifyHost(false);

        SSLContext context = TransportSupport.createSslContext(sslOptions);

        try (TestAmqpPeer testPeer = new TestAmqpPeer(context, true);) {
            String connOptions = "?transport.keyStoreLocation=" + CLIENT_MULTI_KEYSTORE + "&" +
                                 "transport.keyStorePassword=" + PASSWORD + "&" +
                                 "transport.trustStoreLocation=" + CLIENT_JKS_TRUSTSTORE + "&" +
                                 "transport.trustStorePassword=" + PASSWORD;
            Connection connection = testFixture.establishConnecton(testPeer, true, connOptions, null, null, true);

            Socket socket = testPeer.getClientSocket();
            assertTrue(socket instanceof SSLSocket);
            assertNotNull(((SSLSocket) socket).getSession().getPeerPrincipal());

            testPeer.expectClose();
            connection.close();
        }
    }

    @Test(timeout = 20000)
    public void testCreateAndCloseSslConnectionWithAlias() throws Exception {
        doConnectionWithAliasTestImpl(CLIENT_KEY_ALIAS, CLIENT_DN);
        doConnectionWithAliasTestImpl(CLIENT2_KEY_ALIAS, CLIENT2_DN);
    }

    private void doConnectionWithAliasTestImpl(String alias, String expectedDN) throws Exception, JMSException, SSLPeerUnverifiedException, IOException {
        TransportSslOptions sslOptions = new TransportSslOptions();
        sslOptions.setKeyStoreLocation(BROKER_JKS_KEYSTORE);
        sslOptions.setTrustStoreLocation(BROKER_JKS_TRUSTSTORE);
        sslOptions.setKeyStorePassword(PASSWORD);
        sslOptions.setTrustStorePassword(PASSWORD);
        sslOptions.setVerifyHost(false);

        SSLContext context = TransportSupport.createSslContext(sslOptions);

        try (TestAmqpPeer testPeer = new TestAmqpPeer(context, true);) {
            String connOptions = "?transport.keyStoreLocation=" + CLIENT_MULTI_KEYSTORE + "&" +
                                 "transport.keyStorePassword=" + PASSWORD + "&" +
                                 "transport.trustStoreLocation=" + CLIENT_JKS_TRUSTSTORE + "&" +
                                 "transport.trustStorePassword=" + PASSWORD + "&" +
                                 "transport.keyAlias=" + alias;
            Connection connection = testFixture.establishConnecton(testPeer, true, connOptions, null, null, true);

            Socket socket = testPeer.getClientSocket();
            assertTrue(socket instanceof SSLSocket);
            SSLSession session = ((SSLSocket) socket).getSession();

            Certificate[] peerCertificates = session.getPeerCertificates();
            assertNotNull(peerCertificates);

            Certificate cert = peerCertificates[0];
            assertTrue(cert instanceof X509Certificate);
            String dn = ((X509Certificate)cert).getSubjectX500Principal().getName();
            assertEquals("Unexpected certificate DN", expectedDN, dn);

            testPeer.expectClose();
            connection.close();
        }
    }

    @Test(timeout = 20000)
    public void testCreateConnectionWithAliasThatDoesNotExist() throws Exception {
        doCreateConnectionWithInvalidAliasTestImpl(ALIAS_DOES_NOT_EXIST);
    }

    @Test(timeout = 20000)
    public void testCreateConnectionWithAliasThatDoesNotRepresentKeyEntry() throws Exception {
        doCreateConnectionWithInvalidAliasTestImpl(ALIAS_CA_CERT);
    }

    private void doCreateConnectionWithInvalidAliasTestImpl(String alias) throws Exception, IOException {
        TransportSslOptions sslOptions = new TransportSslOptions();
        sslOptions.setKeyStoreLocation(BROKER_JKS_KEYSTORE);
        sslOptions.setTrustStoreLocation(BROKER_JKS_TRUSTSTORE);
        sslOptions.setKeyStorePassword(PASSWORD);
        sslOptions.setTrustStorePassword(PASSWORD);
        sslOptions.setVerifyHost(false);

        SSLContext context = TransportSupport.createSslContext(sslOptions);

        try (TestAmqpPeer testPeer = new TestAmqpPeer(context, true);) {
            String connOptions = "?transport.keyStoreLocation=" + CLIENT_MULTI_KEYSTORE + "&" +
                                 "transport.keyStorePassword=" + PASSWORD + "&" +
                                 "transport.trustStoreLocation=" + CLIENT_JKS_TRUSTSTORE + "&" +
                                 "transport.trustStorePassword=" + PASSWORD + "&" +
                                 "transport.keyAlias=" + alias;

            // DONT use a test fixture, we will drive it directly (because creating the connection will fail).
            JmsConnectionFactory factory = new JmsConnectionFactory("amqps://127.0.0.1:" + testPeer.getServerPort() + connOptions);
            try {
                factory.createConnection();
                fail("Expected exception to be thrown");
            } catch (JMSException jmse) {
                // Expected
            }

            assertNull("Attempt should have failed locally, peer should not have accepted any TCP connection", testPeer.getClientSocket());
        }
    }

    /**
     * Checks that configuring different SSLContext instances using different client key
     * stores via {@link JmsConnectionFactory#setSslContext(SSLContext)} results
     * in different certificates being observed server side following handshake.
     *
     * @throws Exception if an unexpected error is encountered
     */
    @Test(timeout = 20000)
    public void testCreateConnectionWithSslContextOverride() throws Exception {
        assertNotEquals(CLIENT_JKS_KEYSTORE, CLIENT2_JKS_KEYSTORE);
        assertNotEquals(CLIENT_DN, CLIENT2_DN);

        // Connect providing the Client 1 details via context override, expect Client1 DN.
        doConnectionWithSslContextOverride(CLIENT_JKS_KEYSTORE, CLIENT_DN);
        // Connect providing the Client 2 details via context override, expect Client2 DN instead.
        doConnectionWithSslContextOverride(CLIENT2_JKS_KEYSTORE, CLIENT2_DN);
    }

    private void doConnectionWithSslContextOverride(String clientKeyStorePath, String expectedDN) throws Exception {
        TransportSslOptions serverSslOptions = new TransportSslOptions();
        serverSslOptions.setKeyStoreLocation(BROKER_JKS_KEYSTORE);
        serverSslOptions.setTrustStoreLocation(BROKER_JKS_TRUSTSTORE);
        serverSslOptions.setKeyStorePassword(PASSWORD);
        serverSslOptions.setTrustStorePassword(PASSWORD);
        serverSslOptions.setVerifyHost(false);

        SSLContext serverContext = TransportSupport.createSslContext(serverSslOptions);

        TransportSslOptions clientSslOptions = new TransportSslOptions();
        clientSslOptions.setKeyStoreLocation(clientKeyStorePath);
        clientSslOptions.setTrustStoreLocation(CLIENT_JKS_TRUSTSTORE);
        clientSslOptions.setKeyStorePassword(PASSWORD);
        clientSslOptions.setTrustStorePassword(PASSWORD);

        SSLContext clientContext = TransportSupport.createSslContext(clientSslOptions);

        try (TestAmqpPeer testPeer = new TestAmqpPeer(serverContext, true);) {
            JmsConnectionFactory factory = new JmsConnectionFactory("amqps://localhost:" + testPeer.getServerPort());
            factory.setSslContext(clientContext);

            testPeer.expectSaslPlain("guest", "guest");
            testPeer.expectOpen();
            testPeer.expectBegin();

            Connection connection = factory.createConnection("guest", "guest");
            connection.start();

            Socket socket = testPeer.getClientSocket();
            assertTrue(socket instanceof SSLSocket);
            SSLSession session = ((SSLSocket) socket).getSession();

            Certificate[] peerCertificates = session.getPeerCertificates();
            assertNotNull(peerCertificates);

            Certificate cert = peerCertificates[0];
            assertTrue(cert instanceof X509Certificate);
            String dn = ((X509Certificate)cert).getSubjectX500Principal().getName();
            assertEquals("Unexpected certificate DN", expectedDN, dn);

            testPeer.expectClose();
            connection.close();
        }
    }

    /**
     * Checks that configuring an SSLContext instance via
     * {@link JmsConnectionFactory#setSslContext(SSLContext)} overrides URI config
     * for store location etc, resulting in a different certificate being observed
     * server side following handshake.
     *
     * @throws Exception if an unexpected error is encountered
     */
    @Test(timeout = 20000)
    public void testCreateConnectionWithSslContextOverrideAndURIConfig() throws Exception {
        assertNotEquals(CLIENT_JKS_KEYSTORE, CLIENT2_JKS_KEYSTORE);
        assertNotEquals(CLIENT_DN, CLIENT2_DN);

        // Connect without providing a context, expect Client1 DN.
        doConnectionWithSslContextOverrideAndURIConfig(null, CLIENT_DN);

        TransportSslOptions clientSslOptions = new TransportSslOptions();
        clientSslOptions.setKeyStoreLocation(CLIENT2_JKS_KEYSTORE);
        clientSslOptions.setTrustStoreLocation(CLIENT_JKS_TRUSTSTORE);
        clientSslOptions.setKeyStorePassword(PASSWORD);
        clientSslOptions.setTrustStorePassword(PASSWORD);

        SSLContext clientContext = TransportSupport.createSslContext(clientSslOptions);

        // Connect providing the Client 2 details via context override, expect Client2 DN instead.
        doConnectionWithSslContextOverrideAndURIConfig(clientContext, CLIENT2_DN);
    }

    private void doConnectionWithSslContextOverrideAndURIConfig(SSLContext clientContext, String expectedDN) throws Exception {
        TransportSslOptions serverSslOptions = new TransportSslOptions();
        serverSslOptions.setKeyStoreLocation(BROKER_JKS_KEYSTORE);
        serverSslOptions.setTrustStoreLocation(BROKER_JKS_TRUSTSTORE);
        serverSslOptions.setKeyStorePassword(PASSWORD);
        serverSslOptions.setTrustStorePassword(PASSWORD);
        serverSslOptions.setVerifyHost(false);

        SSLContext serverContext = TransportSupport.createSslContext(serverSslOptions);

        try (TestAmqpPeer testPeer = new TestAmqpPeer(serverContext, true);) {
            String connOptions = "?transport.keyStoreLocation=" + CLIENT_JKS_KEYSTORE + "&" +
                    "transport.keyStorePassword=" + PASSWORD + "&" +
                    "transport.trustStoreLocation=" + CLIENT_JKS_TRUSTSTORE + "&" +
                    "transport.trustStorePassword=" + PASSWORD;

            JmsConnectionFactory factory = new JmsConnectionFactory("amqps://localhost:" + testPeer.getServerPort() + connOptions);
            factory.setSslContext(clientContext);

            testPeer.expectSaslPlain("guest", "guest");
            testPeer.expectOpen();
            testPeer.expectBegin();

            Connection connection = factory.createConnection("guest", "guest");
            connection.start();

            Socket socket = testPeer.getClientSocket();
            assertTrue(socket instanceof SSLSocket);
            SSLSession session = ((SSLSocket) socket).getSession();

            Certificate[] peerCertificates = session.getPeerCertificates();
            assertNotNull(peerCertificates);

            Certificate cert = peerCertificates[0];
            assertTrue(cert instanceof X509Certificate);
            String dn = ((X509Certificate)cert).getSubjectX500Principal().getName();
            assertEquals("Unexpected certificate DN", expectedDN, dn);

            testPeer.expectClose();
            connection.close();
        }
    }
}
