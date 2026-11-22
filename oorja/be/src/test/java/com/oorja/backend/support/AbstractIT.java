package com.oorja.backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oorja.backend.config.JwtService;
import com.oorja.backend.dto.request.CreateBookingRequest;
import com.oorja.backend.dto.request.CreateOrderRequest;
import com.oorja.backend.dto.request.VerifyPaymentRequest;
import com.oorja.backend.dto.response.CreateOrderResponse;
import com.oorja.backend.service.EmailService;
import com.oorja.backend.service.RazorpayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for every test that needs the real application: the full Spring context, a real PostgreSQL (Testcontainers) with
 * all Flyway migrations applied and {@code ddl-auto=validate}, the real security filter chain, and MockMvc on top.
 * <p>
 * Only the two outbound integrations are faked: {@link RazorpayService} (the payment gateway) and {@link EmailService}
 * (SMTP). Everything else - repositories, row locks, unique indexes, transactions - is real, which is the point.
 * <p>
 * Test isolation without truncating tables: every test gets its own customers and its own calendar day
 * ({@link #freshDay()}), so seat counters and refund/payment rows never overlap between tests.
 */
@SpringBootTest(properties = {
        "app.jwt.secret=test-only-jwt-secret-0123456789-abcdefghijklmnopqrstuv",
        "app.admin.password=Test-Only-Admin-Password-42",
        "app.cors.allowed-origins=http://localhost:5173",
        "app.mail.enabled=false",
        "booking.slots.capacity=10",
        "booking.slots.min-lead-hours=0",
        "booking.slots.max-advance-days=3650",
        // Keep background jobs from firing mid-test and racing the assertions.
        "booking.slots.hold-sweep-interval-ms=86400000",
        "refund.jobs.dispatch-interval-ms=86400000",
        "refund.jobs.poll-interval-ms=86400000",
        // 50-way concurrency tests: refund dispatch needs a second (REQUIRES_NEW) connection per in-flight request.
        "spring.datasource.hikari.maximum-pool-size=60",
        "spring.datasource.hikari.minimum-idle=5"
})
@AutoConfigureMockMvc
@ExtendWith(DockerAvailableCondition.class)
public abstract class AbstractIT {

    protected static final LocalTime NINE = LocalTime.of(9, 0);
    protected static final LocalTime ELEVEN = LocalTime.of(11, 0);
    protected static final LocalTime THIRTEEN = LocalTime.of(13, 0);
    protected static final LocalTime FIFTEEN = LocalTime.of(15, 0);

    /** Shared by every test class in the JVM so no two tests ever pick the same calendar day. */
    private static final AtomicInteger DAY_OFFSET = new AtomicInteger(3);

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper om;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JwtService jwtService;

    @MockBean protected RazorpayService razorpay;
    @MockBean protected EmailService emailService;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> pg = SharedPostgres.get();
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    // ------------------------------------------------------------------ gateway fake

    @BeforeEach
    void stubGateway() {
        when(razorpay.createOrder(anyLong(), any())).thenAnswer(inv -> {
            Number amount = inv.getArgument(0);
            return CreateOrderResponse.builder()
                    .orderId("order_" + shortId() + shortId())
                    .amount(amount.longValue())
                    .currency("INR")
                    .keyId("rzp_test_key")
                    .build();
        });
        when(razorpay.verifySignature(any())).thenReturn(true);
        when(razorpay.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(razorpay.getPublicKeyId()).thenReturn("rzp_test_key");
        when(razorpay.refund(any(), anyLong(), any(), any(), any()))
                .thenAnswer(inv -> new RazorpayService.RefundResult("rfnd_" + shortId() + shortId(), "processing"));
    }

    // ------------------------------------------------------------------ data helpers

    /** A customer and one plant, inserted directly so tests don't depend on the OTP/registration flow. */
    protected record TestCustomer(long id, String email, long plantId) {}

    /** A captured payment: the order it was made against, the gateway payment id, and the amount in paise. */
    protected record Paid(String orderId, String paymentId, long amountPaise) {}

    /** A booking funded by a captured payment. */
    protected record PaidBooking(long bookingId, Paid paid, LocalDate day, LocalTime time) {}

    protected static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    protected TestCustomer newCustomer() {
        String u = shortId();
        String email = u + "@test.io";
        Long id = jdbc.queryForObject(
                "insert into customers(customer_code,name,email) values (?,?,?) returning id",
                Long.class, "C" + u, "Test " + u, email);
        Long plantId = jdbc.queryForObject(
                "insert into plants(plant_code,name,type,capacity_kw,location,customer_id) values (?,?,?,?,?,?) returning id",
                Long.class, "P" + u, "Plant " + u, "RESIDENTIAL", 5, "Pune", id);
        return new TestCustomer(id, email, plantId);
    }

    protected String tokenFor(TestCustomer c) {
        return jwtService.generateToken(c.email(), c.id(), "CUSTOMER");
    }

    protected String adminToken() {
        return jwtService.generateToken("admin@test.io", 1L, "SUPER_ADMIN");
    }

    /** A calendar day no other test uses, comfortably more than 48h away (so cancellations land in the full-refund tier). */
    protected LocalDate freshDay() {
        return LocalDate.now().plusDays(DAY_OFFSET.incrementAndGet());
    }

    protected int booked(LocalDate day, LocalTime time) {
        var rows = jdbc.queryForList("select booked_count from booking_slots where slot_date=? and slot_time=?",
                Integer.class, day, java.sql.Time.valueOf(time));
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    // ------------------------------------------------------------------ HTTP helpers

    protected ResultActions doGet(String url, String token) throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.get(url);
        if (token != null) {
            b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(b);
    }

    /** POST JSON. {@code headers} are alternating name, value pairs. A null body sends no content at all. */
    protected ResultActions doPost(String url, String token, Object body, String... headers) throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.post(url);
        if (token != null) {
            b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        if (body != null) {
            String json = body instanceof String s ? s : om.writeValueAsString(body);
            b.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(b);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return om.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected int statusOf(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    // ------------------------------------------------------------------ business flows (through the real HTTP API)

    protected CreateOrderRequest orderRequest(TestCustomer c, String serviceId, LocalDate day, LocalTime time) {
        return new CreateOrderRequest(serviceId, null, null, "rcpt_" + shortId(), c.plantId(), day, time,
                "Cleaning", null, "Pune");
    }

    protected CreateBookingRequest bookingRequest(TestCustomer c, String orderId, LocalDate day, LocalTime time) {
        return new CreateBookingRequest(c.plantId(), "Cleaning", day, time, null, "Pune", orderId, "Cleaning", false);
    }

    /** create-order (holds the seat) then verify (captures). Returns what was paid. */
    protected Paid pay(TestCustomer c, String serviceId, LocalDate day, LocalTime time) throws Exception {
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, serviceId, day, time)).andExpect(status().isOk()));
        String orderId = order.get("orderId").asText();
        long amount = order.get("amount").asLong();
        String paymentId = "pay_" + shortId();
        JsonNode verified = body(doPost("/api/customer/payments/verify", tokenFor(c),
                new VerifyPaymentRequest(orderId, paymentId, "sig")).andExpect(status().isOk()));
        assertTrue(verified.get("verified").asBoolean(), "verify should capture the payment");
        return new Paid(orderId, paymentId, amount);
    }

    /** The whole happy path: hold a seat, pay, and turn the payment into a booking. */
    protected PaidBooking paidBooking(TestCustomer c, String serviceId, LocalDate day, LocalTime time) throws Exception {
        Paid paid = pay(c, serviceId, day, time);
        JsonNode booking = body(doPost("/api/customer/bookings", tokenFor(c),
                bookingRequest(c, paid.orderId(), day, time)).andExpect(status().isOk()));
        return new PaidBooking(booking.get("id").asLong(), paid, day, time);
    }

    protected PaidBooking paidBooking(TestCustomer c, LocalDate day, LocalTime time) throws Exception {
        return paidBooking(c, "cleaning", day, time);
    }

    /** Razorpay's payment.captured webhook body for an order. */
    protected String capturedWebhook(String orderId, String paymentId, long amountPaise) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                + "\",\"order_id\":\"" + orderId + "\",\"amount\":" + amountPaise + "}}}}";
    }

    /** Razorpay's refund.processed / refund.failed webhook body. */
    protected String refundWebhook(String event, String refundId, String status, long amountPaise) {
        return "{\"event\":\"" + event + "\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"" + refundId
                + "\",\"status\":\"" + status + "\",\"amount\":" + amountPaise + "}}}}";
    }

    protected ResultActions webhook(String rawBody, String eventId) throws Exception {
        return doPost("/api/webhooks/razorpay", null, rawBody,
                "X-Razorpay-Signature", "valid-by-stub", "X-Razorpay-Event-Id", eventId);
    }
}
