/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.pulsar;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.pulsar.internal.PulsarDeserializationSchema;
import org.apache.flink.streaming.connectors.pulsar.internal.PulsarOptions;
import org.apache.flink.streaming.connectors.pulsar.testutils.PulsarContainer;
import org.apache.flink.streaming.util.TestStreamEnvironment;
import org.apache.flink.test.util.SuccessException;

import io.streamnative.tests.pulsar.service.PulsarServiceSpec;
import io.streamnative.tests.pulsar.service.testcontainers.containers.PulsarStandaloneContainer;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import java.io.IOException;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * pulsar auth tests.
 */
public class PulsarAuthTest {

    private static final Logger log = LoggerFactory.getLogger(PulsarAuthTest.class);
    private static String serviceUrl;
    private static String adminUrl;
    private static PulsarStandaloneContainer container;
    private static String authPluginClassName;
    private static String authParamsString;

    @BeforeClass
    public static void prepare() throws Exception {

        log.info("-------------------------------------------------------------------------");
        log.info("    Starting PulsarTestBase ");
        log.info("-------------------------------------------------------------------------");

        System.setProperty("pulsar.systemtest.image", "streamnative/pulsar:2.6.0-sn-18-3");

        PulsarServiceSpec spec = PulsarServiceSpec.builder()
                .clusterName("standalone-" + UUID.randomUUID())
                .enableContainerLogging(false)
                .build();
        container = new PulsarContainer(spec.clusterName())
                .withClasspathResourceMapping("standalone.conf", "/pulsar/conf/standalone.conf", BindMode.READ_ONLY)
                .withClasspathResourceMapping("my-secret.key", "/pulsar/conf/my-secret.key", BindMode.READ_ONLY)
                .withClasspathResourceMapping("client.conf", "/pulsar/conf/client.conf", BindMode.READ_ONLY)
                .withNetwork(Network.newNetwork())
                .withNetworkAliases(PulsarStandaloneContainer.NAME + "-" + spec.clusterName());
        if (spec.enableContainerLogging()) {
            container.withLogConsumer(new Slf4jLogConsumer(log));
        }
        container.start();
        serviceUrl = container.getExposedPlainTextServiceUrl();
        adminUrl = container.getExposedHttpServiceUrl();

        Thread.sleep(30 * 1000);
        log.info("-------------------------------------------------------------------------");
        log.info("Successfully started pulsar service at cluster " + spec.clusterName());
        log.info("-------------------------------------------------------------------------");

    }

    @AfterClass
    public static void shutDownServices() throws Exception {
        log.info("-------------------------------------------------------------------------");
        log.info("    Shut down PulsarTestBase ");
        log.info("-------------------------------------------------------------------------");

        TestStreamEnvironment.unsetAsContext();

        if (container != null) {
            container.stop();
        }

        log.info("-------------------------------------------------------------------------");
        log.info("    PulsarTestBase finished");
        log.info("-------------------------------------------------------------------------");
    }

    @Before
    public void setParams() {
        authPluginClassName = "org.apache.pulsar.client.impl.auth.AuthenticationToken";
        authParamsString =
                "token:eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMSJ9.2AgtxHe8-2QBV529B5DrRtpuqP6RJjrk21Mhnomfivo";
    }

    @Test
    public void testSource() throws Exception {
        final StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        final String topic = "persistent://public/default/test";
        int total = 20;
        try (PulsarClient client = PulsarClient.builder()
                .authentication(authPluginClassName, authParamsString)
                .serviceUrl(serviceUrl).build()) {
            sendMessage(topic, client, total);
        }

        final Properties properties = new Properties();
        properties.setProperty("topic", topic);
        properties.setProperty(PulsarOptions.AUTH_PLUGIN_CLASSNAME_KEY, authPluginClassName);
        properties.setProperty(PulsarOptions.AUTH_PARAMS_KEY, authParamsString);
        final FlinkPulsarSource<String> stringFlinkPulsarSource = new FlinkPulsarSource<String>(
                serviceUrl,
                adminUrl,
                new StringPulsarDeserializationSchema(),
                properties
        )
                .setStartFromEarliest();
        environment.addSource(stringFlinkPulsarSource)
                .map(new IgnoreMap(total))
                .print();
        try {
            environment.execute();
        } catch (Exception e) {
            if (!(ExceptionUtils.getRootCause(e) instanceof SuccessException)) {
                Assert.fail();
            }
        }
    }

    protected void sendMessage(String topic, PulsarClient pulsarClient, int total) throws PulsarClientException {
        try (
                Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                        .topic(topic)
                        .create();
        ) {
            pulsarClient.newConsumer()
                    .topic(topic)
                    .subscriptionName("test")
                    .subscribe()
                    .close();
            for (int i = 0; i < total; i++) {
                producer.send("test-string-" + i);
            }
        }
    }

    /**
     * ignore map for test.
     */
    public static class IgnoreMap implements MapFunction<String, String> {

        private int total;

        private AtomicInteger current;

        public IgnoreMap(int total) {
            this.total = total;
            this.current = new AtomicInteger(0);
        }

        @Override
        public String map(String value) throws Exception {
            if (current.incrementAndGet() == total) {
                throw new SuccessException();
            }
            return value;
        }
    }

    /**
     * string deserialization for schema.
     */
    private static class StringPulsarDeserializationSchema implements PulsarDeserializationSchema<String> {

        private SimpleStringSchema simpleStringSchema = new SimpleStringSchema();

        @Override
        public boolean isEndOfStream(String nextElement) {
            return false;
        }

        @Override
        public String deserialize(Message message) throws IOException {
            return simpleStringSchema.deserialize(message.getData());
        }

        @Override
        public TypeInformation<String> getProducedType() {
            return simpleStringSchema.getProducedType();
        }
    }
}
