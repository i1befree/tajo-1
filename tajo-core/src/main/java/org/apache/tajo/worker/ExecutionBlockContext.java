/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.worker;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.tajo.ExecutionBlockId;
import org.apache.tajo.QueryUnitAttemptId;
import org.apache.tajo.TajoProtos;
import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.engine.query.QueryContext;
import org.apache.tajo.ipc.QueryMasterProtocol;
import org.apache.tajo.master.cluster.WorkerConnectionInfo;
import org.apache.tajo.rpc.NettyClientBase;
import org.apache.tajo.rpc.NullCallback;
import org.apache.tajo.rpc.RpcChannelFactory;
import org.apache.tajo.rpc.RpcConnectionPool;
import org.apache.tajo.storage.HashShuffleAppenderManager;
import org.apache.tajo.storage.StorageUtil;
import org.apache.tajo.util.ApplicationIdUtils;
import org.apache.tajo.util.NetUtils;
import org.apache.tajo.util.Pair;
import org.apache.tajo.worker.event.TaskRunnerStartEvent;
import org.jboss.netty.channel.ConnectTimeoutException;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.util.Timer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.tajo.ipc.TajoWorkerProtocol.*;

public class ExecutionBlockContext {
  /** class logger */
  private static final Log LOG = LogFactory.getLog(ExecutionBlockContext.class);

  private TaskRunnerManager manager;
  public AtomicInteger completedTasksNum = new AtomicInteger();
  public AtomicInteger succeededTasksNum = new AtomicInteger();
  public AtomicInteger runningTasksNum = new AtomicInteger();
  public AtomicInteger killedTasksNum = new AtomicInteger();
  public AtomicInteger failedTasksNum = new AtomicInteger();
  public AtomicInteger containerIdSeq = new AtomicInteger();

  private ClientSocketChannelFactory channelFactory;
  // for temporal or intermediate files
  private FileSystem localFS;
  // for input files
  private FileSystem defaultFS;
  private ExecutionBlockId executionBlockId;
  private QueryContext queryContext;
  private String plan;

  private ExecutionBlockSharedResource resource;

  private TajoQueryEngine queryEngine;
  private RpcConnectionPool connPool;
  private InetSocketAddress qmMasterAddr;
  private WorkerConnectionInfo queryMaster;
  private TajoConf systemConf;
  // for the doAs block
  private UserGroupInformation taskOwner;

  private Reporter reporter;

  //key is a local absolute path of temporal directories
  private Map<String, ThreadPoolExecutor> fetcherExecutorMap = Maps.newHashMap();
  private AtomicBoolean stop = new AtomicBoolean();

  private final LinkedList<TaskRunnerId> taskRunnerIdPool = Lists.newLinkedList();
  // It keeps all of the query unit attempts while a TaskRunner is running.
  private final ConcurrentMap<QueryUnitAttemptId, Task> tasks = Maps.newConcurrentMap();

  private final ConcurrentMap<TaskRunnerId, TaskRunnerHistory> histories = Maps.newConcurrentMap();

  public ExecutionBlockContext(TaskRunnerManager manager, TaskRunnerStartEvent event, WorkerConnectionInfo queryMaster)
      throws Throwable {
    this.manager = manager;
    this.executionBlockId = event.getExecutionBlockId();
    this.connPool = RpcConnectionPool.getPool(manager.getTajoConf());
    this.queryMaster = queryMaster;
    this.systemConf = manager.getTajoConf();
    this.reporter = new Reporter();
    this.defaultFS = TajoConf.getTajoRootDir(systemConf).getFileSystem(systemConf);
    this.localFS = FileSystem.getLocal(systemConf);

    // Setup QueryEngine according to the query plan
    // Here, we can setup row-based query engine or columnar query engine.
    this.queryEngine = new TajoQueryEngine(systemConf);
    this.queryContext = event.getQueryContext();
    this.plan = event.getPlan();
    this.resource = new ExecutionBlockSharedResource();

    init();
  }

  public void init() throws Throwable {

    LOG.info("Tajo Root Dir: " + systemConf.getVar(TajoConf.ConfVars.ROOT_DIR));
    LOG.info("Worker Local Dir: " + systemConf.getVar(TajoConf.ConfVars.WORKER_TEMPORAL_DIR));

    this.qmMasterAddr = NetUtils.createSocketAddr(queryMaster.getHost(), queryMaster.getQueryMasterPort());
    LOG.info("QueryMaster Address:" + qmMasterAddr);

    UserGroupInformation.setConfiguration(systemConf);
    // TODO - 'load credential' should be implemented
    // Getting taskOwner
    UserGroupInformation taskOwner = UserGroupInformation.createRemoteUser(systemConf.getVar(TajoConf.ConfVars.USERNAME));

    // initialize DFS and LocalFileSystems
    this.taskOwner = taskOwner;

    // initialize LocalDirAllocator
    LocalDirAllocator lDirAllocator =  manager.getWorkerContext().getLocalDirAllocator();

    ThreadFactoryBuilder builder = new ThreadFactoryBuilder();
    ThreadFactory fetcherFactory = builder.setNameFormat("Fetcher executor #%d").build();

    Iterable<Path> iter = lDirAllocator.getAllLocalPathsToRead(".", systemConf);
    for (Path localDir : iter){
      if(!fetcherExecutorMap.containsKey(localDir)){
        ThreadPoolExecutor fetcherExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
            systemConf.getIntVar(TajoConf.ConfVars.SHUFFLE_FETCHER_PARALLEL_EXECUTION_MAX_NUM), fetcherFactory);
        fetcherExecutorMap.put(localDir.toUri().getRawPath(), fetcherExecutor);
      }

    }
    this.reporter.startReporter();

    // resource intiailization
    try {
      this.resource.initialize(queryContext, plan);
    } catch (Throwable e) {
      getQueryMasterStub().killQuery(null, executionBlockId.getQueryId().getProto(), NullCallback.get());
      throw e;
    }
  }

  public ExecutionBlockSharedResource getSharedResource() {
    return resource;
  }

  public QueryMasterProtocol.QueryMasterProtocolService.Interface getQueryMasterStub()
      throws NoSuchMethodException, ConnectTimeoutException, ClassNotFoundException {
    NettyClientBase clientBase = null;
    try {
      clientBase = connPool.getConnection(qmMasterAddr, QueryMasterProtocol.class, true);
      return clientBase.getStub();
    } finally {
      connPool.releaseConnection(clientBase);
    }
  }

  public void stop(){
    if(stop.getAndSet(true)){
      return;
    }

    taskRunnerIdPool.clear();

    try {
      reporter.stop();
    } catch (InterruptedException e) {
      LOG.error(e);
    }

    // If ExecutionBlock is stopped, all running or pending tasks will be marked as failed.
    for (Task task : tasks.values()) {
      if (task.getStatus() == TajoProtos.TaskAttemptState.TA_PENDING ||
          task.getStatus() == TajoProtos.TaskAttemptState.TA_RUNNING) {
        task.setState(TajoProtos.TaskAttemptState.TA_FAILED);
        try{
          task.abort();
        } catch (Throwable e){
          LOG.error(e);
        }
      }
    }
    tasks.clear();

    resource.release();
    try {
      for(ExecutorService executorService : fetcherExecutorMap.values()){
        executorService.shutdownNow();
      }
      fetcherExecutorMap.clear();
      releaseShuffleChannelFactory();
    } catch (Throwable e) {
      LOG.error(e.getMessage(), e);
    }
  }

  public TajoConf getConf() {
    return manager.getTajoConf();
  }

  public FileSystem getLocalFS() {
    return localFS;
  }

  public FileSystem getDefaultFS() {
    return defaultFS;
  }

  public LocalDirAllocator getLocalDirAllocator() {
    return manager.getWorkerContext().getLocalDirAllocator();
  }

  public TajoQueryEngine getTQueryEngine() {
    return queryEngine;
  }

  public Map<QueryUnitAttemptId, Task> getTasks() {
    return tasks;
  }

  public boolean containsTask(QueryUnitAttemptId queryUnitAttemptId) {
    return tasks.containsKey(queryUnitAttemptId);
  }

  public Task getTask(QueryUnitAttemptId taskId) {
    return tasks.get(taskId);
  }

  public void putTask(QueryUnitAttemptId queryUnitAttemptId, Task task) {
    tasks.put(queryUnitAttemptId, task);
  }

  public Task removeTask(QueryUnitAttemptId queryUnitAttemptId) {
    return tasks.remove(queryUnitAttemptId);
  }

  public ExecutorService getFetchLauncher(String outPutPath) {
    // for random access
    ExecutorService fetcherExecutor = null;
    int minScheduledSize = Integer.MAX_VALUE;

    for (Map.Entry<String, ThreadPoolExecutor> entry : fetcherExecutorMap.entrySet()) {
      if (outPutPath.startsWith(entry.getKey())) {
        fetcherExecutor = entry.getValue();
        break;
      }

      int scheduledSize = entry.getValue().getQueue().size();
      if(minScheduledSize > scheduledSize){
        fetcherExecutor = entry.getValue();
        minScheduledSize = scheduledSize;
      }
    }
    return fetcherExecutor;
  }

  // for the local temporal dir
  public Path createBaseDir() throws IOException {
    // the base dir for an output dir
    String baseDir = getBaseOutputDir(executionBlockId).toString();
    Path baseDirPath = localFS.makeQualified(getLocalDirAllocator().getLocalPathForWrite(baseDir, systemConf));
    return baseDirPath;
  }

  public static Path getBaseOutputDir(ExecutionBlockId executionBlockId) {
    Path workDir =
        StorageUtil.concatPath(
            executionBlockId.getQueryId().toString(),
            "output",
            String.valueOf(executionBlockId.getId()));
    return workDir;
  }

  public static Path getBaseInputDir(ExecutionBlockId executionBlockId) {
    Path workDir =
        StorageUtil.concatPath(
            executionBlockId.getQueryId().toString(),
            "in",
            executionBlockId.toString());
    return workDir;
  }

  public ExecutionBlockId getExecutionBlockId() {
    return executionBlockId;
  }

  public void stopTask(TaskRunnerId id){
    manager.stopTask(id);
  }

  public TajoWorker.WorkerContext getWorkerContext(){
    return manager.getWorkerContext();
  }

  public void releaseTaskRunnerId(TaskRunnerId taskRunnerId) {
    taskRunnerIdPool.addLast(taskRunnerId);
  }

  /* Shareable taskRunnerId must be released back to the taskRunnerIdPool when a taskRunner completed. */
  public TaskRunnerId getTaskRunnerId() {
    synchronized (taskRunnerIdPool) {
      if(taskRunnerIdPool.isEmpty()){
        taskRunnerIdPool.addLast(newTaskRunnerId());
      }
      return taskRunnerIdPool.pollFirst();
    }
  }

  public TaskRunnerId newTaskRunnerId() {
    ApplicationAttemptId applicationAttemptId = ApplicationIdUtils.createApplicationAttemptId(executionBlockId);
    return new TaskRunnerId(applicationAttemptId, getWorkerContext().getConnectionInfo().getId() + containerIdSeq.incrementAndGet());
  }

  public void addTaskHistory(TaskRunnerId taskRunnerId, QueryUnitAttemptId quAttemptId, TaskHistory taskHistory) {
    getTaskRunnerHistory(taskRunnerId).addTaskHistory(quAttemptId, taskHistory);
  }

  public TaskRunnerHistory getTaskRunnerHistory(TaskRunnerId taskRunnerId){
    histories.putIfAbsent(taskRunnerId, new TaskRunnerHistory(taskRunnerId, executionBlockId));
    return histories.get(taskRunnerId);
  }

  protected ClientSocketChannelFactory getShuffleChannelFactory(){
    if(channelFactory == null) {
      int workerNum = getConf().getIntVar(TajoConf.ConfVars.SHUFFLE_RPC_CLIENT_WORKER_THREAD_NUM);
      channelFactory = RpcChannelFactory.createClientChannelFactory("Fetcher", workerNum);
    }
    return channelFactory;
  }

  public Timer getRPCTimer() {
    return manager.getRPCTimer();
  }

  protected void releaseShuffleChannelFactory(){
    if(channelFactory != null) {
      channelFactory.shutdown();
      channelFactory.releaseExternalResources();
      channelFactory = null;
    }
  }

  private void sendExecutionBlockReport(ExecutionBlockReport reporter) throws Exception {
    getQueryMasterStub().doneExecutionBlock(null, reporter, NullCallback.get());
  }

  protected void reportExecutionBlock(ExecutionBlockId ebId) {
    ExecutionBlockReport.Builder reporterBuilder = ExecutionBlockReport.newBuilder();
    reporterBuilder.setEbId(ebId.getProto());
    reporterBuilder.setReportSuccess(true);
    reporterBuilder.setSucceededTasks(succeededTasksNum.get());
    try {
      List<IntermediateEntryProto> intermediateEntries = Lists.newArrayList();
      List<HashShuffleAppenderManager.HashShuffleIntermediate> shuffles =
          getWorkerContext().getHashShuffleAppenderManager().close(ebId);
      if (shuffles == null) {
        reporterBuilder.addAllIntermediateEntries(intermediateEntries);
        sendExecutionBlockReport(reporterBuilder.build());
        return;
      }

      IntermediateEntryProto.Builder intermediateBuilder = IntermediateEntryProto.newBuilder();
      IntermediateEntryProto.PageProto.Builder pageBuilder = IntermediateEntryProto.PageProto.newBuilder();
      FailureIntermediateProto.Builder failureBuilder = FailureIntermediateProto.newBuilder();

      for (HashShuffleAppenderManager.HashShuffleIntermediate eachShuffle: shuffles) {
        List<IntermediateEntryProto.PageProto> pages = Lists.newArrayList();
        List<FailureIntermediateProto> failureIntermediateItems = Lists.newArrayList();

        for (Pair<Long, Integer> eachPage: eachShuffle.getPages()) {
          pageBuilder.clear();
          pageBuilder.setPos(eachPage.getFirst());
          pageBuilder.setLength(eachPage.getSecond());
          pages.add(pageBuilder.build());
        }

        for(Pair<Long, Pair<Integer, Integer>> eachFailure: eachShuffle.getFailureTskTupleIndexes()) {
          failureBuilder.clear();
          failureBuilder.setPagePos(eachFailure.getFirst());
          failureBuilder.setStartRowNum(eachFailure.getSecond().getFirst());
          failureBuilder.setEndRowNum(eachFailure.getSecond().getSecond());
          failureIntermediateItems.add(failureBuilder.build());
        }
        intermediateBuilder.clear();

        intermediateBuilder.setEbId(ebId.getProto())
            .setHost(getWorkerContext().getConnectionInfo().getHost() + ":" +
                getWorkerContext().getConnectionInfo().getPullServerPort())
            .setTaskId(-1)
            .setAttemptId(-1)
            .setPartId(eachShuffle.getPartId())
            .setVolume(eachShuffle.getVolume())
            .addAllPages(pages)
            .addAllFailures(failureIntermediateItems);
        intermediateEntries.add(intermediateBuilder.build());
      }

      // send intermediateEntries to QueryMaster
      reporterBuilder.addAllIntermediateEntries(intermediateEntries);

    } catch (Throwable e) {
      LOG.error(e.getMessage(), e);
      reporterBuilder.setReportSuccess(false);
      if (e.getMessage() == null) {
        reporterBuilder.setReportErrorMessage(e.getClass().getSimpleName());
      } else {
        reporterBuilder.setReportErrorMessage(e.getMessage());
      }
    }
    try {
      sendExecutionBlockReport(reporterBuilder.build());
    } catch (Throwable e) {
      // can't send report to query master
      LOG.fatal(e.getMessage(), e);
      throw new RuntimeException(e);
    }
  }

  protected class Reporter {
    private Thread reporterThread;
    private AtomicBoolean reporterStop = new AtomicBoolean();
    private static final int PROGRESS_INTERVAL = 1000;
    private static final int MAX_RETRIES = 10;

    public Reporter() {
      this.reporterThread = new Thread(createReporterThread());
      this.reporterThread.setName("Task reporter");
    }

    public void startReporter(){
      this.reporterThread.start();
    }

    Runnable createReporterThread() {

      return new Runnable() {
        int remainingRetries = MAX_RETRIES;
        QueryMasterProtocol.QueryMasterProtocolService.Interface masterStub;
        @Override
        public void run() {
          while (!reporterStop.get() && !Thread.interrupted()) {
            try {
              masterStub = getQueryMasterStub();

              if(tasks.size() == 0){
                masterStub.ping(null, getExecutionBlockId().getProto(), NullCallback.get());
              } else {
                for (Task task : new ArrayList<Task>(tasks.values())){

                  if (task.isRunning() && task.isProgressChanged()) {
                    task.updateProgress();
                    masterStub.statusUpdate(null, task.getReport(), NullCallback.get());
                    task.getContext().setProgressChanged(false);
                  } else {
                    task.updateProgress();
                  }
                }
              }
            } catch (Throwable t) {
              LOG.error(t.getMessage(), t);
              remainingRetries -=1;
              if (remainingRetries == 0) {
                ReflectionUtils.logThreadInfo(LOG, "Communication exception", 0);
                LOG.warn("Last retry, exiting ");
                throw new RuntimeException(t);
              }
            } finally {
              if (remainingRetries > 0 && !reporterStop.get()) {
                synchronized (reporterThread) {
                  try {
                    reporterThread.wait(PROGRESS_INTERVAL);
                  } catch (InterruptedException e) {
                  }
                }
              }
            }
          }
        }
      };
    }

    public void stop() throws InterruptedException {
      if (reporterStop.getAndSet(true)) {
        return;
      }

      if (reporterThread != null) {
        // Intent of the lock is to not send an interupt in the middle of an
        // umbilical.ping or umbilical.statusUpdate
        synchronized (reporterThread) {
          //Interrupt if sleeping. Otherwise wait for the RPC call to return.
          reporterThread.notifyAll();
        }
      }
    }
  }
}
