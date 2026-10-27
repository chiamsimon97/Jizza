package com.jizza.engine.devices.maintenance;

import com.jizza.engine.api.event.AutonomyPaused;
import com.jizza.engine.api.event.MembershipChanged;
import com.jizza.engine.devices.broker.CommandBroker;
import com.jizza.engine.devices.config.DeviceProperties;
import com.jizza.engine.devices.gateway.DeviceHub;
import com.jizza.engine.devices.registry.DeviceRegistryService;
import com.jizza.engine.persistence.repository.DeviceChallengeRepository;
import com.jizza.engine.persistence.repository.DeviceLogRepository;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the gateway tidy: expires commands past their deadline (which fails or re-asks the step
 * that waits on them), stops everything when the kill switch is thrown, revokes a person's devices
 * in a workspace they left, and drops old logs and challenges.
 */
@Component
public class DeviceSweeper {

  private static final Logger log = LoggerFactory.getLogger(DeviceSweeper.class);

  private final CommandBroker broker;
  private final DeviceRegistryService registry;
  private final DeviceHub hub;
  private final DeviceLogRepository logs;
  private final DeviceChallengeRepository challenges;
  private final DeviceProperties props;

  public DeviceSweeper(CommandBroker broker, DeviceRegistryService registry, DeviceHub hub,
                       DeviceLogRepository logs, DeviceChallengeRepository challenges,
                       DeviceProperties props) {
    this.broker = broker;
    this.registry = registry;
    this.hub = hub;
    this.logs = logs;
    this.challenges = challenges;
    this.props = props;
  }

  @Scheduled(fixedDelayString = "${jizza.devices.sweep-interval:PT5S}")
  @SchedulerLock(name = "jizza-device-deadlines", lockAtMostFor = "PT1M")
  public void expireOverdue() {
    int expired = broker.expireOverdue();
    if (expired > 0) log.info("Expired {} device command(s) past their deadline", expired);
  }

  @Scheduled(fixedDelayString = "${jizza.devices.cleanup-interval:PT1H}")
  @SchedulerLock(name = "jizza-device-cleanup", lockAtMostFor = "PT10M")
  @Transactional
  public void cleanUp() {
    logs.deleteOlderThan(Instant.now().minus(props.getLogRetention()));
    challenges.deleteExpired(Instant.now());
    hub.forgetStale();
  }

  @EventListener
  public void onAutonomyPaused(AutonomyPaused event) {
    int cancelled = broker.cancelForUser(event.userId(), "Autonomy was paused");
    if (cancelled > 0) log.info("Kill switch: cancelled {} device command(s)", cancelled);
  }

  @EventListener
  public void onMembershipChanged(MembershipChanged event) {
    if (event.kind() == MembershipChanged.Kind.ROLE_CHANGED) return;
    registry.revokeOwnedBy(event.userId(), event.workspaceId(), "left the account");
  }
}
