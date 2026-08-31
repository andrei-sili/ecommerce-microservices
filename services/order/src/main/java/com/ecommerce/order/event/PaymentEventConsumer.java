package com.ecommerce.order.event;

import com.ecommerce.order.client.ProductReservationClient;
import com.ecommerce.order.config.RabbitMQConfig;
import com.ecommerce.order.exception.UpstreamServiceException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes payment events from {@code order.payment-events} with manual ack. The message is acked
 * only after the side-effect transaction commits (via {@link PaymentEventHandlerService}).
 * Permanent failures (amount mismatch, anomalous state) are nacked without requeue → DLQ. Transient
 * failures (upstream 5xx) are nacked with requeue on the first attempt, then dead-lettered on
 * retry.
 *
 * <p>Event payloads are camelCase (see contract). Deserialization uses a dedicated {@link
 * JsonMapper} configured independently of the REST snake_case {@code ObjectMapper}.
 */
@Component
public class PaymentEventConsumer {

  private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

  // Dedicated camelCase mapper — event contract uses camelCase, REST uses snake_case.
  private static final JsonMapper EVENT_MAPPER =
      JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();

  private final PaymentEventHandlerService handlerService;
  private final ProductReservationClient reservationClient;

  public PaymentEventConsumer(
      PaymentEventHandlerService handlerService, ProductReservationClient reservationClient) {
    this.handlerService = handlerService;
    this.reservationClient = reservationClient;
  }

  @RabbitListener(queues = RabbitMQConfig.PAYMENT_QUEUE, ackMode = "MANUAL")
  public void handle(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    String eventType = message.getMessageProperties().getType();

    try {
      String payload = new String(message.getBody(), StandardCharsets.UTF_8);
      PaymentEvent event = EVENT_MAPPER.readValue(payload, PaymentEvent.class);

      switch (eventType != null ? eventType : "") {
        case "PaymentCompleted" -> handleCompleted(event, channel, tag);
        case "PaymentFailed" -> handleFailed(event, channel, tag);
        case "PaymentCancelled" -> handleCancelled(event, channel, tag);
        default -> {
          log.warn("Unknown payment event type '{}', acking to avoid redelivery loop", eventType);
          channel.basicAck(tag, false);
        }
      }
    } catch (PermanentConsumerFailureException e) {
      log.error("Permanent failure processing payment event, routing to DLQ: {}", e.getMessage());
      channel.basicNack(tag, false, false);
    } catch (JacksonException e) {
      // Jackson 3 throws this (unchecked) where Jackson 2 threw IOException. Without this branch a
      // malformed payload would fall to the transient handler below and be REQUEUED rather than
      // dead-lettered -- a poison message looping instead of parking in the DLQ.
      log.error("Failed to deserialize payment event payload: {}", e.getMessage());
      channel.basicNack(tag, false, false);
    } catch (IOException e) {
      log.error("Failed to deserialize payment event payload: {}", e.getMessage());
      channel.basicNack(tag, false, false);
    } catch (Exception e) {
      // Transient failure: requeue on first attempt, dead-letter on retry.
      boolean redelivered = message.getMessageProperties().isRedelivered();
      if (redelivered) {
        log.error(
            "Transient failure on redelivered message, dead-lettering: {}", e.getMessage(), e);
        channel.basicNack(tag, false, false);
      } else {
        log.warn("Transient failure, requeuing for one retry: {}", e.getMessage());
        channel.basicNack(tag, false, true);
      }
    }
  }

  private void handleCompleted(PaymentEvent event, Channel channel, long tag) throws IOException {
    PaymentEventHandlerService.HandlerResult result;
    try {
      result = handlerService.processPaymentCompleted(event);
    } catch (PermanentConsumerFailureException e) {
      throw e; // rethrow to outer handler for DLQ routing
    }

    if (result == PaymentEventHandlerService.HandlerResult.NO_OP) {
      channel.basicAck(tag, false);
      return;
    }

    // PAID_COMMITTED: call Product commit endpoint outside the transaction.
    UUID orderId = UUID.fromString(event.orderId());
    try {
      reservationClient.commit(orderId);
      channel.basicAck(tag, false);
    } catch (ProductReservationClient.ReservationNotActiveException e) {
      // Hold expired before payment landed. Order is already marked PAID + inbox row exists.
      // Route to DLQ for manual reconciliation; inbox row prevents reprocessing.
      log.error(
          "CRITICAL reconciliation: reservation expired for PAID order {} (commit 409),"
              + " routing to DLQ",
          orderId);
      channel.basicNack(tag, false, false);
    }
  }

  private void handleFailed(PaymentEvent event, Channel channel, long tag) throws IOException {
    PaymentEventHandlerService.HandlerResult result = handlerService.processPaymentFailed(event);

    if (result == PaymentEventHandlerService.HandlerResult.NO_OP) {
      channel.basicAck(tag, false);
      return;
    }

    // NEED_RELEASE: reservation was held, release it now.
    UUID orderId = UUID.fromString(event.orderId());
    try {
      reservationClient.release(orderId);
    } catch (UpstreamServiceException e) {
      // Best-effort: reservation will expire via Product's sweeper.
      log.warn(
          "Failed to release reservation for PAYMENT_FAILED order {}: {}", orderId, e.getMessage());
    }
    channel.basicAck(tag, false);
  }

  private void handleCancelled(PaymentEvent event, Channel channel, long tag) throws IOException {
    PaymentEventHandlerService.HandlerResult result = handlerService.processPaymentCancelled(event);

    if (result == PaymentEventHandlerService.HandlerResult.NO_OP) {
      channel.basicAck(tag, false);
      return;
    }

    // NEED_RELEASE: reservation was held, release it now.
    UUID orderId = UUID.fromString(event.orderId());
    try {
      reservationClient.release(orderId);
    } catch (UpstreamServiceException e) {
      log.warn("Failed to release reservation for CANCELLED order {}: {}", orderId, e.getMessage());
    }
    channel.basicAck(tag, false);
  }
}
