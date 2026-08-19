package io.github.flinktelegrambridge.config;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
/** Creates Redis clients with optional ACL credentials. */
public final class InfrastructureConnections {
  private InfrastructureConnections() {}
  public static RedisClient createRedisClient(AppConfig config) { RedisURI uri=RedisURI.create(config.redisUri()); if(!config.redisUsername().isBlank()) uri.setUsername(config.redisUsername()); if(!config.redisPassword().isBlank()) uri.setPassword(config.redisPassword().toCharArray()); return RedisClient.create(uri); }
}
