package star.tracto.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static star.tracto.preprocessing.TokensFixture.candidatesAfter;

/**
 * End-to-end test of the classes of a project annotated with Lombok (Step 43): their generated members (getters, a
 * builder) do not exist in the sources. {@code tools/deps/resolve_maven.py} writes the delomboked copies of the files
 * that use Lombok in {@code processed_libs/delombok/<source root>/}; the symbol solver reads them before the sources of
 * the project, so the generated members are offered. The sources of the project are unchanged (the text seen by the
 * model).
 */
class LombokTokensTest {

    @TempDir
    Path tempDir;

    private TokensFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new TokensFixture(tempDir);
        fixture.write("src/main/java/com/acme/Protocol.java", """
                package com.acme;

                import lombok.Getter;
                import lombok.RequiredArgsConstructor;

                @Getter
                @RequiredArgsConstructor
                public enum Protocol {
                    V12("1.2", 12),
                    V16("1.6", 16);

                    private final String version;
                    private final int number;

                    public String label() {
                        return "OCPP " + version;
                    }
                }
                """);
        fixture.write("src/main/java/com/acme/Order.java", """
                package com.acme;

                import lombok.Builder;
                import lombok.Value;

                @Value
                @Builder
                public class Order {
                    String id;
                    int quantity;
                }
                """);
        fixture.write("src/main/java/com/acme/Shop.java", """
                package com.acme;

                public class Shop {
                    public static Protocol protocol() {
                        return Protocol.V16;
                    }

                    public static Order order() {
                        return Order.builder().id("a").quantity(2).build();
                    }
                }
                """);
        // The delomboked copies (as delombok writes them), in a folder of their own per source root, with the manifest
        fixture.write("processed_libs/delombok/roots.json", """
                {"roots": [{"folder": "src__main__java", "sourceRoot": "src/main/java"}]}
                """);
        fixture.write("processed_libs/delombok/src__main__java/com/acme/Protocol.java", """
                package com.acme;

                public enum Protocol {
                    V12("1.2", 12), V16("1.6", 16);
                    private final String version;
                    private final int number;

                    public String label() {
                        return "OCPP " + version;
                    }

                    @java.lang.SuppressWarnings("all")
                    @lombok.Generated
                    private Protocol(final String version, final int number) {
                        this.version = version;
                        this.number = number;
                    }

                    @java.lang.SuppressWarnings("all")
                    @lombok.Generated
                    public String getVersion() {
                        return this.version;
                    }

                    @java.lang.SuppressWarnings("all")
                    @lombok.Generated
                    public int getNumber() {
                        return this.number;
                    }
                }
                """);
        fixture.write("processed_libs/delombok/src__main__java/com/acme/Order.java", """
                package com.acme;

                public final class Order {
                    private final String id;
                    private final int quantity;

                    @java.lang.SuppressWarnings("all")
                    @lombok.Generated
                    Order(final String id, final int quantity) {
                        this.id = id;
                        this.quantity = quantity;
                    }

                    @java.lang.SuppressWarnings("all")
                    @lombok.Generated
                    public static class OrderBuilder {
                        @java.lang.SuppressWarnings("all")
                        @lombok.Generated
                        private String id;
                        @java.lang.SuppressWarnings("all")
                        @lombok.Generated
                        private int quantity;

                        @java.lang.SuppressWarnings("all")
                        @lombok.Generated
                        OrderBuilder() {
                        }

                        @java.lang.SuppressWarnings("all")
                        @lombok.Generated
                        public Order.OrderBuilder id(final String id) {
                            this.id = id;
                            return this;
                        }

                        @java.lang.SuppressWarnings("all")
                        @lombok.Generated
                        public Order.OrderBuilder quantity(final int quantity) {
                            this.quantity = quantity;
                            return this;
                        }

                        @java.lang.SuppressWarnings("all")
                        @lombok.Generated
                        public Order build() {
                            return new Order(this.id, this.quantity);
                        }
                    }

                    @java.lang.SuppressWarnings("all")
                    @lombok.Generated
                    public static Order.OrderBuilder builder() {
                        return new Order.OrderBuilder();
                    }

                    @java.lang.SuppressWarnings("all")
                    @lombok.Generated
                    public String getId() {
                        return this.id;
                    }

                    @java.lang.SuppressWarnings("all")
                    @lombok.Generated
                    public int getQuantity() {
                        return this.quantity;
                    }
                }
                """);
        fixture.write("src/test/java/com/acme/ShopTest.java", """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;

                public class ShopTest {
                    @Test
                    public void testProtocol() {
                        for (Protocol protocol : Protocol.values()) {
                            assertEquals("OCPP " + protocol.getVersion(), protocol.label());
                        }
                    }

                    @Test
                    public void testOrder() {
                        Order order = Shop.order();
                        assertEquals(2, order.getQuantity());
                    }

                    @Test
                    public void testBuilder() {
                        assertEquals("b", Order.builder().id("b").build().getId());
                    }
                }
                """);
    }

    @Test
    void theMembersGeneratedByLombokAreOffered() throws IOException {
        fixture.generate();

        assertEquals(Map.of(), fixture.unexpectedTokens());
        Map<String, JsonNode> datapoints = fixture.tokensDatapoints();
        List<String> protocol = candidatesAfter(datapoints.get("testProtocol_split_0"), "assertEquals(\"OCPP \"+protocol.");
        assertTrue(protocol.containsAll(List.of("getVersion", "getNumber", "label")), protocol.toString());
        List<String> order = candidatesAfter(datapoints.get("testOrder_split_0"), "assertEquals(2,order.");
        assertTrue(order.containsAll(List.of("getId", "getQuantity")), order.toString());
        List<String> builder = candidatesAfter(datapoints.get("testBuilder_split_0"), "assertEquals(\"b\",Order.");
        assertTrue(builder.contains("builder"), builder.toString());
        List<String> setter = candidatesAfter(datapoints.get("testBuilder_split_0"), "assertEquals(\"b\",Order.builder().");
        assertTrue(setter.containsAll(List.of("id", "quantity", "build")), setter.toString());
    }
}
