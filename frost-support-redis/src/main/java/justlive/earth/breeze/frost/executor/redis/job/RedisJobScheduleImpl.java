package justlive.earth.breeze.frost.executor.redis.job;

import java.util.concurrent.ExecutorService;
import javax.annotation.PostConstruct;
import org.redisson.api.CronSchedule;
import org.redisson.api.RList;
import org.redisson.api.RListMultimap;
import org.redisson.api.RScheduledExecutorService;
import org.redisson.api.RScheduledFuture;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import justlive.earth.breeze.frost.api.model.JobInfo;
import justlive.earth.breeze.frost.core.config.JobProperties;
import justlive.earth.breeze.frost.core.job.JobDispatchWrapper;
import justlive.earth.breeze.frost.core.job.JobSchedule;
import justlive.earth.breeze.frost.core.persistence.JobRepository;
import justlive.earth.breeze.frost.core.registry.HeartBeat;
import justlive.earth.breeze.frost.executor.redis.config.SystemProperties;
import justlive.earth.breeze.snow.common.base.exception.Exceptions;
import lombok.extern.slf4j.Slf4j;

/**
 * redis任务定时实现
 * 
 * @author wubo
 *
 */
@Slf4j
@Profile(SystemProperties.PROFILE_CENTER)
@Component
public class RedisJobScheduleImpl implements JobSchedule {

  @Autowired
  RedissonClient redissonClient;

  @Autowired
  @Qualifier("redisson-executor")
  ExecutorService executorService;

  @Autowired
  JobProperties jobProps;

  @Autowired
  JobRepository jobRepository;

  @PostConstruct
  void init() {

    // 心跳
    RTopic<HeartBeat> topic = redissonClient.getTopic(String.join(SystemProperties.SEPERATOR,
        SystemProperties.EXECUTOR_PREFIX, HeartBeat.class.getName()));
    topic.addListener((channel, msg) -> {
      if (log.isDebugEnabled()) {
        log.debug("heartBeat: {}", msg);
      }
    });

    RScheduledExecutorService service =
        redissonClient.getExecutorService(SystemProperties.CENTER_PREFIX);
    service.registerWorkers(jobProps.getParallel(), executorService);
  }

  @Override
  public String addJob(String jobId, String cron) {

    RScheduledExecutorService service =
        redissonClient.getExecutorService(SystemProperties.CENTER_PREFIX);
    RScheduledFuture<?> future =
        service.scheduleAsync(new JobDispatchWrapper(jobId), CronSchedule.of(cron));

    String taskId = future.getTaskId();
    RListMultimap<String, String> listmap =
        redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, JobSchedule.class.getName()));
    listmap.put(jobId, taskId);

    return taskId;
  }


  @Override
  public String refreshJob(String jobId, String cron) {

    this.pauseJob(jobId);
    return this.addJob(jobId, cron);
  }

  @Override
  public void pauseJob(String jobId) {

    RListMultimap<String, String> listmap =
        redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, JobSchedule.class.getName()));
    RList<String> list = listmap.get(jobId);
    RScheduledExecutorService service =
        redissonClient.getExecutorService(SystemProperties.CENTER_PREFIX);
    for (String id : list) {
      service.cancelTask(id);
    }
    listmap.removeAll(jobId);
  }

  @Override
  public String resumeJob(String jobId) {

    JobInfo jobInfo = this.getJobInfo(jobId);
    return this.addJob(jobId, jobInfo.getCron());
  }

  @Override
  public void removeJob(String jobId) {

    this.pauseJob(jobId);
  }

  @Override
  public void triggerJob(String jobId) {

    RScheduledExecutorService service =
        redissonClient.getExecutorService(SystemProperties.CENTER_PREFIX);
    service.submit(new JobDispatchWrapper(jobId));
  }

  private JobInfo getJobInfo(String jobId) {
    JobInfo jobInfo = jobRepository.findJobInfoById(jobId);
    if (jobInfo == null) {
      throw Exceptions.fail("300002", "未查询到Job记录");
    }
    return jobInfo;
  }
}
