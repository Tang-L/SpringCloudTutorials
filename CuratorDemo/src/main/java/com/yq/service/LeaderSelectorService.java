package com.yq.service;

import com.yq.Constant.PathConstants;
import com.yq.config.ZkConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.leader.Participant;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.CloseableUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.consul.serviceregistry.ConsulRegistration;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Simple to Introduction
 * className: LeaderSelectorService
 *
 * @author EricYang
 * @version 2018/9/2 12:01
 */
@Service
@Slf4j
@Getter
public class LeaderSelectorService {
    @Autowired
    private ConsulRegistration registration;

    @Autowired
    ZkConfig zKConfig;

    private static final int CLIENT_QTY = 10;
    private CuratorFramework client = null;
    private MyLeaderSelectorListener selector = null;
    private String instanceId = null;

    //存放自己的任务列表，Map<String, String>  <uuid, content>
    private List<Map<String, String>> mySubList = new ArrayList<>();
    private CountDownLatch  countDownLatch = new CountDownLatch(1);

    public void init() throws Exception
    {
        instanceId = registration.getInstanceId();
        try {
            client =
                //CuratorFrameworkFactory.newClient("127.0.0.1:2181", new ExponentialBackoffRetry(1000, 3));
                CuratorFrameworkFactory.newClient(zKConfig.getZkServers(), 30*1000, 15*1000, new ExponentialBackoffRetry(1000, 3));
            client.start();
            //先注册自己 人，然后才watch其他人
            registerMySelf();
            watchMySubList();

            selector = new MyLeaderSelectorListener(client, PathConstants.LEADER_PATH, instanceId);
            selector.start();

            //leader选举完毕再观察总的任务列表
            watchAllSubList();
            watchWorkerList();

            countDownLatch.await();
        } finally {
            log.info("Shutting down...");
            CloseableUtils.closeQuietly(selector);
            CloseableUtils.closeQuietly(client);
        }
    }

    public void registerMySelf() throws Exception {
        Long threadId = Thread.currentThread().getId();
        String workerPath = PathConstants.WORKER_PATH + "/" + instanceId;
        try {
            Stat stat = client.checkExists().forPath(workerPath);
            if(stat == null) {
                String currentTimeStr = LocalDateTime.now().toString();
                client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(workerPath, currentTimeStr.getBytes("UTF-8"));
                log.info("create workerPath={} in registerMySelf. threadId={}", workerPath, threadId);
            }
            else {
                log.warn("{} has already been registered. threadId={}", workerPath, threadId);
            }
        }
        catch (Exception ex) {
            log.error("{} registered. threadId={}. exception", workerPath, threadId, ex);
        }
    }

    public void watchAllSubList() {
        try {
            PathChildrenCache watcher = new PathChildrenCache(
                    client,
                    PathConstants.ALL_SUB_PATH,
                    true    // if cache data
            );
            watcher.getListenable().addListener((client1, event) -> {
                Long threadId = Thread.currentThread().getId();
                ChildData data = event.getData();
                if (data == null) {
                    log.info("No data in event[{}]", event);
                } else {
                    if (event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED) {
                        /* 新增加了哪个任务，通过data的getPath和getData就知道了
                        type=[CHILD_ADDED], path=[/allSubList/A001], data=[ca001], stat=[1534,1534,1535953560268,1535953560268,0,0,0,0,5,0,1534
                        */
                        String uuid = data.getPath().substring(PathConstants.ALL_SUB_PATH.length() +1);
                        String content = new String(data.getData());
                        log.info("ALL_SUB_PATH新增task(uuid={}, content={})，需要当前leader分配task给worker. threadId={}", uuid, content, threadId);
                        if(selector.getLeaderSelector().hasLeadership()) {
                            log.info("InstanceId={} is leader now,开始处理新task, uuid={}, content={}, threadId={}",
                                    instanceId, uuid, content, threadId);
                            selector.distributeNewTask2Worker(uuid, content);
                        }
                        else {
                            log.info("InstanceId={} is not leader, 不用处理. threadId={}", instanceId, threadId);
                        }
                    }
                    else if (event.getType() == PathChildrenCacheEvent.Type.CHILD_REMOVED) {
                        /* 哪个task 取消了，非常清楚就知道了
                        type=[CHILD_REMOVED], path=[/allSubList/A001], data=[ca001], stat=[1526,1526,1535953361117,1535953361117,0,0,0,0,5,0,1526
                        */
                        log.info("ALL_SUB_PATH 有task取消了，需要执行该task的worker进行更新, 也就是所有的worker都需要自己检查一下该任务是否在自己手上");
                        String uuid = data.getPath().substring(PathConstants.ALL_SUB_PATH.length() + 1);
                        String content = new String(data.getData());
                        log.info("开始处理取消的task, instanceId={}, uuid={}, content={}, threadId={}", instanceId, uuid, content, threadId);
                        processCancelledTask(uuid);
                    }else if (event.getType() == PathChildrenCacheEvent.Type.CHILD_UPDATED) {
                        log.info("ALL_SUB_PATH task更新，不用管. threadId={}", threadId);
                    } else {
                        log.info("Receive event: type={}, path={}, data={}, stat={}",
                                event.getType() ,data.getPath(), new String(data.getData()), data.getStat());
                    }
                }
            });
            watcher.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
            log.info("Register zk watcher 观察总的任务列表successfully!");
        }
        catch (Exception ex) {
            log.error("观察总的任务列表 was interrupted.", ex);
        }
    }

    public void processCancelledTask(String uuid) {
        String workerTaskPath = PathConstants.MY_SUB_PATH + "/" + instanceId + "/" + uuid;
        try {
            //检查该worker是否有任务uuid
            Stat stat = client.checkExists().forPath(workerTaskPath);
            if(stat == null) {
                log.info("task={} is not on InstanceId={}",uuid, instanceId);
            }
            else {
                client.delete().forPath(workerTaskPath);
            }
        }
        catch (Exception ex) {
            log.error("processCancelledTask workerTaskPath={}. exception", workerTaskPath, ex);
        }
    }

    /*
     自己的任务列表，一致需要关注，然后去执行，并更新对应的状态
     */
    public void watchMySubList() {
        String instanceId = registration.getInstanceId();
        String myPath = PathConstants.MY_SUB_PATH + "/" + instanceId;
        try {
            PathChildrenCache watcher = new PathChildrenCache(
                    client,
                    myPath,
                    true
            );
            watcher.getListenable().addListener((client1, event) -> {
                ChildData data = event.getData();
                if (data == null) {
                    log.info("No data in event[{}]", event);
                } else {
                    if (event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED) {
                        /* 新增加了哪个任务，很多就知道了
                        type=[CHILD_ADDED], path=[/myWorkerList/sub-service-8082-2103334695/A002],
                        data=[1535881598520], stat=[1463,1463,1535881598527,1535881598527,0,0,0,100654189908262946,13,0,1463
                        */
                        String uuid = data.getPath().substring(PathConstants.MY_SUB_PATH.length() + 1 + instanceId.length() + 1);
                        String content = new String(data.getData());
                        log.info("我的任务新增一个task，需要执行该task并更新该task的状态, uuid={}, content={}", uuid, content);
                    }
                    else if (event.getType() == PathChildrenCacheEvent.Type.CHILD_REMOVED) {
                        /* 哪个task 取消了，非常清楚就知道了
                        type=[CHILD_REMOVED], path=[/myWorkerList/sub-service-8082-1774221102],
                        data=[1535881276839], stat=[1449,1449,1535881276849,1535881276849,0,0,0,100654189908262942,13,0,1449
                        */
                        String uuid = data.getPath().substring(PathConstants.MY_SUB_PATH.length() + 1 + instanceId.length() + 1);
                        String content = new String(data.getData());
                        log.info("我的任务取消一个task，需要取消该task 并更新该task的状态. uuid={}, content={}", uuid, content);
                    }else if (event.getType() == PathChildrenCacheEvent.Type.CHILD_UPDATED) {
                        log.info("MY_SUBLIST_PATH task更新，不用管");
                    } else {
                        log.info("Receive event: type={}, path={}, data={}, stat={}",
                                event.getType() ,data.getPath(), new String(data.getData()), data.getStat());
                    }
                }
            });
            watcher.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
            log.info("Register zk watcher 观察自己的任务列表successfully!");
        }
        catch (Exception ex) {
            log.error("观察自己的任务列表 was interrupted.", ex);
        }
    }

    private void watchWorkerList() {
        try {
            //观察当前的worker， 有几个实例在工作
            PathChildrenCache watcher = new PathChildrenCache(
                    client,
                    PathConstants.WORKER_PATH,
                    true    // if cache data
            );
            watcher.getListenable().addListener((client1, event) -> {
                Long threadId = Thread.currentThread().getId();
                ChildData data = event.getData();
                if (data == null) {
                    log.info("No data in event[{}]", event);
                } else {
                    if (event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED) {
                    /*
                        type=[CHILD_ADDED], path=[/myWorkerList/sub-service-8082-2103334695],
                        data=[1535881598520], stat=[1463,1463,1535881598527,1535881598527,0,0,0,100654189908262946,13,0,1463
                    */
                        String path = data.getPath();
                        if (selector.getLeaderSelector().hasLeadership()) {
                            selector.processNewWorker(data.getPath());
                            log.info("新增workerId={}，workerId={}是leader,需要从其他活着的worker转移一些任务到新的worker上. threadId={}",
                                    path, instanceId, Thread.currentThread().getId());
                        }
                        else {
                            log.info("新增workerId={}, workerId={}不是leader，不用处理. threadId={}",
                                    path, instanceId, Thread.currentThread().getId());
                        }
                    }
                    else if (event.getType() == PathChildrenCacheEvent.Type.CHILD_REMOVED) {
                    /* 哪个worker down了，非常清楚就知道了
                        type=[CHILD_REMOVED], path=[/myWorkerList/sub-service-8082-1774221102],
                        data=[1535881276839], stat=[1449,1449,1535881276849,1535881276849,0,0,0,100654189908262942,13,0,1449
                    */
                        String path = data.getPath();
                        if (selector.getLeaderSelector().hasLeadership()) {
                            selector.processDownWorker(data.getPath());
                            log.info("workerId={} down了, workerId={}是leader,需要让其他活着的worker接管该worker上的任务. threadId={}",
                                    path, instanceId,Thread.currentThread().getId());
                        }
                        else {
                            log.info("workerId={} down了，workerId={}不是leader，不用处理. threadId={}",
                                    path, instanceId, Thread.currentThread().getId());
                        }

                    }else if (event.getType() == PathChildrenCacheEvent.Type.CHILD_UPDATED) {
                        log.info("workerList child更新，不用管. threadId={}", threadId);
                    } else {
                        log.info("Receive event: type={}, path={}, data={}, stat={}",
                                event.getType() ,data.getPath(), new String(data.getData()), data.getStat());
                    }
                }
            });
            watcher.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
            log.info("Register zk watcher 观察workerList successfully!");
        }
        catch (Exception ex) {
            log.error(instanceId + " has exception.", ex);
        }
    }
    /*
     自己的任务列表
     */
    public List<String> getMySubList() {
        String workerSubListPath = PathConstants.MY_SUB_PATH + "/" + instanceId;
        List<String> subList = null;
        try {
           subList = client.getChildren().forPath(workerSubListPath);
        }
        catch (Exception ex) {
            log.warn("获取自己的任务列表 was interrupted.", ex);
        }

        return subList;
    }

    /*
     * 获取所有instance的订阅情况
    */
    public Map<String ,List<String>> getAllWorkerSubList() {
        String allWorkerListPath = PathConstants.WORKER_PATH;
        Map<String ,List<String>> workerSubListMap = null;
        try {
            List<String> workerList = client.getChildren().forPath(allWorkerListPath);

            if (workerList != null && !workerList.isEmpty()) {
                workerSubListMap = new HashMap<>();
                for(String workerId : workerList) {
                    String workerSubListPath = PathConstants.MY_SUB_PATH + "/" + workerId;
                    List<String> subList = client.getChildren().forPath(workerSubListPath);

                    if (subList != null && !subList.isEmpty()) {
                        List<String> detailedList = new ArrayList<>();
                        for (String connectorId : subList) {
                            String fullPath = workerSubListPath +  "/" + connectorId;
                            String topicName = getData(fullPath);
                            detailedList.add(connectorId +"---"+ topicName);
                        }
                        workerSubListMap.put(workerId, detailedList);
                    }
                    else {
                        workerSubListMap.put(workerId, subList);
                    }
                }
            }
        }
        catch (Exception ex) {
            log.warn("Get All instance sub list. exception", ex);
        }

        return Collections.unmodifiableMap(workerSubListMap);
    }

    private String getData(String fullPath) {
        String topicStr = null;
        try {
            byte[] topicByte = client.getData().forPath(fullPath);
            topicStr = new String(topicByte, "UTF-8");
        }
        catch (Exception ex) {
            log.warn("Can't read data from fullPath={}, exception.", fullPath, ex);
        }
        return topicStr;
    }

    public Participant getCurrentLeader() {
        Participant leader = null;
        try {
            leader = selector.getLeaderSelector().getLeader();
        }
        catch (Exception ex) {
            log.error("Getting current leader exception", ex);
        }
        return  leader;
    }

    public Collection<Participant> getAllParticipants() {
        Collection<Participant> allParticipants = null;
        try {
            allParticipants = selector.getLeaderSelector().getParticipants();
        }
        catch (Exception ex) {
            log.error("Getting allParticipants exception", ex);
        }
        return  allParticipants;
    }
}
