/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.jms.consumer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.broker.BrokerPlugin;
import org.apache.activemq.broker.BrokerPluginSupport;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.jmx.QueueViewMBean;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.command.MessageDispatch;
import org.apache.qpid.jms.JmsConnection;
import org.apache.qpid.jms.support.AmqpTestSupport;
import org.apache.qpid.jms.support.Wait;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JmsExpiredMessageConsumptionTest extends AmqpTestSupport {

    protected static final Logger LOG = LoggerFactory.getLogger(JmsMessageConsumerTest.class);

    @Override
    protected void addAdditionalBrokerPlugins(List<BrokerPlugin> plugins) {
        @SuppressWarnings("unchecked")
        BrokerPlugin expireOutbound = new BrokerPluginSupport() {

            @Override
            public void preProcessDispatch(MessageDispatch messageDispatch) {
                if (messageDispatch.getMessage() != null) {
                    LOG.info("Preprocessing dispatch: {}", messageDispatch.getMessage().getMessageId());
                    if (messageDispatch.getMessage().getExpiration() != 0) {
                        messageDispatch.getMessage().setExpiration(System.currentTimeMillis() - 1000);
                    }
                }

                super.preProcessDispatch(messageDispatch);
            }

//            @Override
//            public void postProcessDispatch(MessageDispatch messageDispatch) {
//                if (messageDispatch.getMessage() != null) {
//                    LOG.info("Postprocessing dispatch: {}", messageDispatch.getMessage().getMessageId());
//                    if (messageDispatch.getMessage().getExpiration() != 0) {
//                        messageDispatch.getMessage().setExpiration(System.currentTimeMillis() - 1000);
//                    }
//                }
//
//                super.postProcessDispatch(messageDispatch);
//            }

        };

        plugins.add(expireOutbound);
    }

    @Override
    protected void configureBrokerPolicies(BrokerService broker) {
        PolicyMap policyMap = new PolicyMap();
        PolicyEntry defaultEntry = new PolicyEntry();
        defaultEntry.setExpireMessagesPeriod(60000);
        defaultEntry.setUseCache(false);
        policyMap.setDefaultEntry(defaultEntry);

        broker.setDestinationPolicy(policyMap);
    }

    @Test(timeout = 60000)
    public void testConsumerExpiredMessageSync() throws Exception {
        connection = createAmqpConnection();
        connection.start();

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue(name.getMethodName());

        MessageConsumer consumer = session.createConsumer(queue);
        MessageProducer producer = session.createProducer(queue);

        producer.setTimeToLive(500);
        producer.send(session.createTextMessage("Message-1"));

        producer.setTimeToLive(Message.DEFAULT_TIME_TO_LIVE);
        producer.send(session.createTextMessage("Message-2"));

        Message received = consumer.receive(5000);
        assertNotNull(received);
        TextMessage textMessage = (TextMessage) received;
        assertTrue(textMessage.getText().endsWith("2"));

        final QueueViewMBean proxy = getProxyToQueue(name.getMethodName());
        assertTrue("Queued message not consumed.", Wait.waitFor(new Wait.Condition() {

            @Override
            public boolean isSatisified() throws Exception {
                return proxy.getQueueSize() == 0;
            }
        }));

        final QueueViewMBean dlqProxy = getProxyToQueue("ActiveMQ.DLQ");
        assertTrue("Queued message did not get sent to DLQ.", Wait.waitFor(new Wait.Condition() {

            @Override
            public boolean isSatisified() throws Exception {
                return dlqProxy.getQueueSize() == 1;
            }
        }));
    }

    @Test(timeout = 60000)
    public void testConsumerExpiredMessageAsync() throws Exception {
        connection = createAmqpConnection();
        connection.start();

        final CountDownLatch success = new CountDownLatch(1);

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue(name.getMethodName());

        MessageConsumer consumer = session.createConsumer(queue);
        MessageProducer producer = session.createProducer(queue);

        producer.setTimeToLive(500);
        producer.send(session.createTextMessage("Message-1"));

        producer.setTimeToLive(Message.DEFAULT_TIME_TO_LIVE);
        producer.send(session.createTextMessage("Message-2"));

        consumer.setMessageListener(new MessageListener() {

            @Override
            public void onMessage(Message incoming) {
                TextMessage textMessage = (TextMessage) incoming;
                try {
                    if (textMessage.getText().endsWith("2")) {
                        success.countDown();
                    }
                } catch (JMSException e) {
                }
            }
        });

        assertTrue("didn't get expected message", success.await(5, TimeUnit.SECONDS));

        final QueueViewMBean proxy = getProxyToQueue(name.getMethodName());
        assertTrue("Queued message not consumed.", Wait.waitFor(new Wait.Condition() {

            @Override
            public boolean isSatisified() throws Exception {
                return proxy.getQueueSize() == 0;
            }
        }));
    }

    @Test(timeout = 60000)
    public void testConsumerExpirationFilterDisabledSync() throws Exception {
        connection = createAmqpConnection();
        connection.start();

        JmsConnection jmsConnection = (JmsConnection) connection;
        jmsConnection.setLocalMessageExpiry(false);

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue(name.getMethodName());

        MessageConsumer consumer = session.createConsumer(queue);
        MessageProducer producer = session.createProducer(queue);

        producer.setTimeToLive(500);
        producer.send(session.createTextMessage("Message-1"));

        Message received = consumer.receive(5000);
        assertNotNull(received);
        TextMessage textMessage = (TextMessage) received;
        assertTrue(textMessage.getText().endsWith("1"));

        final QueueViewMBean proxy = getProxyToQueue(name.getMethodName());
        assertTrue("Queued message not consumed.", Wait.waitFor(new Wait.Condition() {

            @Override
            public boolean isSatisified() throws Exception {
                return proxy.getQueueSize() == 0;
            }
        }));
    }

    @Test(timeout = 60000)
    public void testConsumerExpirationFilterDisabledAsync() throws Exception {
        connection = createAmqpConnection();
        connection.start();

        JmsConnection jmsConnection = (JmsConnection) connection;
        jmsConnection.setLocalMessageExpiry(false);

        final CountDownLatch success = new CountDownLatch(1);

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue(name.getMethodName());

        MessageConsumer consumer = session.createConsumer(queue);
        MessageProducer producer = session.createProducer(queue);

        producer.setTimeToLive(500);
        producer.send(session.createTextMessage("Message-1"));

        consumer.setMessageListener(new MessageListener() {

            @Override
            public void onMessage(Message incoming) {
                TextMessage textMessage = (TextMessage) incoming;
                try {
                    if (textMessage.getText().endsWith("1")) {
                        success.countDown();
                    }
                } catch (JMSException e) {
                }
            }
        });

        assertTrue("didn't get expected message", success.await(5, TimeUnit.SECONDS));

        final QueueViewMBean proxy = getProxyToQueue(name.getMethodName());
        assertTrue("Queued message not consumed.", Wait.waitFor(new Wait.Condition() {

            @Override
            public boolean isSatisified() throws Exception {
                return proxy.getQueueSize() == 0;
            }
        }));
    }
}