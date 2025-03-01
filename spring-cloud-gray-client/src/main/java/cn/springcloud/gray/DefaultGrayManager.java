package cn.springcloud.gray;

import cn.springcloud.gray.client.config.properties.GrayLoadProperties;
import cn.springcloud.gray.communication.InformationClient;
import cn.springcloud.gray.decision.GrayDecision;
import cn.springcloud.gray.decision.GrayDecisionFactoryKeeper;
import cn.springcloud.gray.model.GrayInstance;
import cn.springcloud.gray.model.GrayService;
import cn.springcloud.gray.model.GrayStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DefaultGrayManager extends CachedGrayManager implements CommunicableGrayManager {

    private Timer updateTimer = new Timer("Gray-Update-Timer", true);
    private GrayLoadProperties grayLoadProperties;
    private GrayClientConfig grayClientConfig;
    private InformationClient informationClient;

    public DefaultGrayManager(
            GrayClientConfig grayClientConfig,
            GrayLoadProperties grayLoadProperties,
            GrayDecisionFactoryKeeper grayDecisionFactoryKeeper,
            InformationClient informationClient,
            Cache<String, List<GrayDecision>> grayDecisionCache) {
        super(grayDecisionFactoryKeeper, grayDecisionCache);
        this.grayLoadProperties = grayLoadProperties;
        this.grayClientConfig = grayClientConfig;
        this.informationClient = informationClient;
    }

    @Override
    public void setup() {
        super.setup();
        updateTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                openForWork();
            }
        }, getGrayClientConfig().getServiceInitializeDelayTimeInMs());
    }

    @Override
    public void shutdown() {
        super.shutdown();
        updateTimer.cancel();
    }

    public void openForWork() {
        if (getGrayInformationClient() != null) {
            log.info("拉取灰度列表");
            doUpdate();
            int timerMs = getGrayClientConfig().getServiceUpdateIntervalTimerInMs();
            if (timerMs > 0) {
                updateTimer.schedule(new UpdateTask(), timerMs, timerMs);
            }
        } else {
            loadPropertiesGrays();
        }

    }

    private void doUpdate() {
        lock.lock();
        try {
            log.debug("更新灰度服务列表...");
            List<GrayInstance> grayInstances = getGrayInformationClient().allGrayInstances();
            Map<String, GrayService> grayServices = new ConcurrentHashMap<>();
            grayInstances.forEach(
                    instance -> {
                        updateGrayInstance(grayServices, instance);
                    });
            joinLoadedGrays(grayServices);
            setGrayServices(grayServices);
        } catch (Exception e) {
            log.error("更新灰度服务列表失败", e);
        } finally {
            lock.unlock();
        }
    }


    private void loadPropertiesGrays() {
        Map<String, GrayService> grayServices = new ConcurrentHashMap<>();
        joinLoadedGrays(grayServices);
        setGrayServices(grayServices);
    }


    /**
     * 加入配置文件中的灰度实例，但不会覆盖列表中的信息
     *
     * @param grayServices 更新的灰度列表
     */
    private void joinLoadedGrays(Map<String, GrayService> grayServices) {
        if (grayLoadProperties != null && grayLoadProperties.isEnabled()) {
            grayLoadProperties.getGrayInstances().forEach(
                    instance -> {
                        if (!grayServices.containsKey(instance.getServiceId())
                                || grayServices.get(instance.getServiceId())
                                .getGrayInstance(instance.getInstanceId()) == null) {
                            if (instance.getGrayStatus() == null) {
                                instance.setGrayStatus(GrayStatus.OPEN);
                            }
                            updateGrayInstance(grayServices, instance);
                        }
                    });
        }
    }

    class UpdateTask extends TimerTask {

        @Override
        public void run() {
            doUpdate();
        }
    }

    public GrayClientConfig getGrayClientConfig() {
        return grayClientConfig;
    }

    @Override
    public InformationClient getGrayInformationClient() {
        return informationClient;
    }
}
