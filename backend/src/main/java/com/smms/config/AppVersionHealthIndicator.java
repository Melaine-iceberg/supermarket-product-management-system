package com.smms.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * 版本健康项（IR-SW-08/GAP-P2-27）：健康检查端点返回应用版本号，
 * 供容器编排或运维脚本在不查询 /actuator/info 的情况下核对部署版本；
 * 构建信息缺失（未打包/单测环境）时回退 "dev"。
 */
@Component
public class AppVersionHealthIndicator implements HealthIndicator {

    private final ObjectProvider<BuildProperties> buildProperties;

    public AppVersionHealthIndicator(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @Override
    public Health health() {
        BuildProperties build = buildProperties.getIfAvailable();
        return Health.up()
                .withDetail("version", build == null ? "dev" : build.getVersion())
                .build();
    }
}
