package com.ecommerce.product.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that releases RESERVED stock holds whose TTL has elapsed. Runs at a configurable
 * fixed delay (default 60 s, both initial and between runs). Calls {@link
 * ReservationService#sweepExpired} in batches of 100 so the sweeper never holds a long transaction
 * against the product table.
 *
 * <p>Tests inject this bean and call {@link #sweep()} directly; the initial delay is set to a very
 * large value in the test context so the scheduled trigger never fires during test execution.
 */
@Component
public class ReservationSweeper {

  private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);
  private static final int BATCH_SIZE = 100;

  private final ReservationService reservationService;

  public ReservationSweeper(ReservationService reservationService) {
    this.reservationService = reservationService;
  }

  @Scheduled(
      fixedDelayString = "${reservation.sweeper.delay-ms:60000}",
      initialDelayString = "${reservation.sweeper.delay-ms:60000}")
  public void sweep() {
    int total = 0;
    int batch;
    do {
      batch = reservationService.sweepExpired(BATCH_SIZE);
      total += batch;
    } while (batch > 0);
    if (total > 0) {
      log.info("Swept {} expired reservation rows", total);
    }
  }
}
