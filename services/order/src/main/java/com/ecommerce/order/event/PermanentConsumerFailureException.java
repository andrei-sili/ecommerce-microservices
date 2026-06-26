package com.ecommerce.order.event;

/**
 * Signals a permanent, non-retryable processing failure: the consumer should nack the message
 * without requeue so it is routed to the DLQ for reconciliation.
 */
public class PermanentConsumerFailureException extends RuntimeException {

  public PermanentConsumerFailureException(String message) {
    super(message);
  }
}
