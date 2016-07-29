
package org.apache.spark.deploy.worker

import org.apache.spark.rpc._
import org.apache.spark._
import org.apache.spark.scheduler.TaskDescription
import org.apache.spark.scheduler.cluster.CoarseGrainedClusterMessages._
import org.apache.spark.serializer.JavaSerializer
import org.apache.spark.util.{ThreadUtils, Utils}

import scala.collection.mutable.HashMap
import scala.util.{Failure, Success}

/**
  * Created by Matteo on 21/07/2016.
  */
class ControllerProxy
(rpcEnvWorker: RpcEnv, val driverUrl: String, val execId: Int) {

  var proxyEndpoint: RpcEndpointRef = _
  val ENDPOINT_NAME: String =
    "ControllerProxy-%s".format(driverUrl.split(":").last + "-" + execId.toString)
  var totalTask: Int = _
  var controllerExecutor: ControllerExecutor = _
  var taskLaunched: Int = 0
  var taskCompleted: Int = 0
  var taskFailed: Int = 0

  val conf = new SparkConf
  val securityMgr = new SecurityManager(conf)
  val rpcEnv = RpcEnv.create("Controller", rpcEnvWorker.address.host, 5555, conf, securityMgr)

  var executorStageId: Int = -1

  def start() {
    proxyEndpoint = rpcEnv.setupEndpoint(ENDPOINT_NAME, createProxyEndpoint(driverUrl))
    // rpcEnv.awaitTermination()
  }

  protected def createProxyEndpoint(driverUrl: String): ProxyEndpoint = {
    new ProxyEndpoint(rpcEnv, driverUrl)
  }

  def getAddress: String = {
    "spark://" + ENDPOINT_NAME + "@" + proxyEndpoint.address.toString
  }


  class ProxyEndpoint(override val rpcEnv: RpcEnv,
                      driverUrl: String) extends ThreadSafeRpcEndpoint with Logging {

    protected val executorIdToAddress = new HashMap[String, RpcAddress]

    private val executorRefMap = new HashMap[String, RpcEndpointRef]

    @volatile var driver: Option[RpcEndpointRef] = Some(rpcEnv.setupEndpointRefByURI(driverUrl))


    def sendToDriver(message: Any): Unit = {
      driver match {
        case Some(ref) => ref.send(message)
        case None =>
          logWarning(
            s"Dropping $message because the connection to driver has not yet been established")
      }
    }

    override def receive: PartialFunction[Any, Unit] = {
      case StatusUpdate(executorId, taskId, state, data) =>
        if (state == TaskState.FINISHED) {
          if (controllerExecutor != null) controllerExecutor.completedTasks += 1
          taskCompleted += 1
          logDebug("EID: %s, Completed: %d, Launched: %d, Total: %d".format(executorId,
            taskCompleted, taskLaunched, totalTask))
          if (taskCompleted == totalTask) {
            driver.get.send(ExecutorFinishedTask(executorId))
            taskCompleted = 0
            taskLaunched = 0
            totalTask = 0
            executorStageId = -1
            if (controllerExecutor != null) controllerExecutor.stop()
          }
        }
        if ((TaskState.LOST == state) || (TaskState.FAILED == state)
          || (TaskState.KILLED == state)){
          taskFailed += 1
          driver.get.send(Bind(execId.toString, executorStageId))
        }
        driver.get.send(StatusUpdate(executorId, taskId, state, data))

      case RegisteredExecutor(hostname) =>
        logInfo("Already Registered Before ACK also driver knows about executor")
      // executorRefMap(executorIdToAddress(execId.toString).host).send(RegisteredExecutor(hostname))

      case RegisterExecutorFailed(message) =>
        executorRefMap(
          executorIdToAddress(execId.toString).host).send(RegisterExecutorFailed(message))

      case LaunchTask(taskId, data) =>
        if (taskLaunched == totalTask && taskFailed == 0) {
          driver.get.send(StatusUpdate(execId.toString, taskId, TaskState.KILLED, data))
        } else {
          executorRefMap(executorIdToAddress(execId.toString).host).send(LaunchTask(taskId, data))
          taskLaunched += 1
          if (taskFailed > 0) taskFailed -= 1
          if (taskLaunched == totalTask) {
            driver.get.send(UnBind(execId.toString))
          }

        }

      case StopExecutor =>
        logInfo("Asked to terminate Executor")
        executorRefMap(executorIdToAddress(execId.toString).host).send(StopExecutor)
        if (controllerExecutor != null) controllerExecutor.stop()

      case Bind(executorId, stageId) =>
        driver.get.send(Bind(executorId, stageId))
        executorStageId = stageId
        taskCompleted = 0
        taskLaunched = 0

      case UnBind(executorId) =>
        driver.get.send(UnBind(executorId))
        if (controllerExecutor != null) controllerExecutor.stop()
        executorStageId = -1

      case ExecutorScaled(execId, cores, newFreeCores) =>
        val deltaFreeCore = cores - (taskLaunched - taskCompleted)
        driver.get.send(ExecutorScaled(execId, cores, deltaFreeCore))


    }

    override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
      case RegisterExecutor(executorId, executorRef, hostPort, cores, logUrls) =>
        logInfo("Forward connection to driver: " + driverUrl)
        rpcEnv.asyncSetupEndpointRefByURI(driverUrl).flatMap { ref =>
          // This is a very fast action so we can use "ThreadUtils.sameThread"
          driver = Some(ref)
          logInfo(ref.address.toString)
          ref.ask[RegisterExecutorResponse](
            RegisterExecutor(executorId, self, hostPort, cores, logUrls))
        }(ThreadUtils.sameThread).onComplete {
          // This is a very fast action so we can use "ThreadUtils.sameThread"
          case Success(msg) => Utils.tryLogNonFatalError {
            Option(self).foreach(_.send(msg)) // msg must be RegisterExecutorResponse
          }
          case Failure(e) => {
            logError(s"Cannot register with driver: $driverUrl", e)
            System.exit(1)
          }
        }(ThreadUtils.sameThread)
        val executorAddress = if (executorRef.address != null) {
          executorRef.address
        } else {
          context.senderAddress
        }
        executorIdToAddress(executorId) = executorAddress
        this.synchronized {
          executorRefMap.put(executorAddress.host, executorRef)
        }
        context.reply(RegisteredExecutor(executorAddress.host))

      case RetrieveSparkProps =>
        val sparkProperties = driver.get.askWithRetry[Seq[(String, String)]](RetrieveSparkProps)
        context.reply(sparkProperties)
    }
  }

}