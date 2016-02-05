package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ ActorRef, Status }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.impl.TaskOpProcessor.Action
import mesosphere.marathon.core.task.tracker.impl.TaskOpProcessorImpl.StatusUpdateActionResolver
import mesosphere.marathon.state.{ PathId, TaskRepository }
import org.apache.mesos.Protos.TaskState._
import org.apache.mesos.Protos.TaskStatus
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

private[tracker] object TaskOpProcessorImpl {

  /**
    * Maps a task status update to the appropriate [[TaskOpProcessor.Action]].
    *
    * @param directTaskTracker a TaskTracker instance that goes directly to the correct taskTracker
    *                          without going through the WhenLeaderActor indirection.
    */
  class StatusUpdateActionResolver(clock: Clock, directTaskTracker: TaskTracker) {
    private[this] val log = LoggerFactory.getLogger(getClass)

    /**
      * Maps the UpdateStatus action to
      *
      * * a Action.Fail if the task does not exist OR ELSE
      * * a Action.Noop if the task does not have to be changed OR ELSE
      * * an Action.Expunge if the TaskStatus update indicates a terminated task OR ELSE
      * * an Action.Update if the tasks existed and the TaskStatus contains new information OR ELSE
      */
    def resolve(taskId: Task.Id, status: TaskStatus)(
      implicit ec: ExecutionContext): Future[Action] = {
      directTaskTracker.task(taskId).map {
        case Some(existingTask) =>
          resolveForExistingTask(existingTask, status)
        case None =>
          Action.Fail(new IllegalStateException(s"$taskId of app [${taskId.appId}] does not exist"))
      }
    }

    /**
      * Calculates the change that needs to performed on this task according to the given task status update
      */
    private[this] def resolveForExistingTask(taskState: Task, statusUpdate: TaskStatus): Action = {
      taskState.launchedTask match {
        case Some(currentLaunched) =>
          statusUpdate.getState match {
            case TASK_ERROR | TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST =>
              Action.Expunge
            case TASK_RUNNING if currentLaunched.status.startedAt.isEmpty => // was staged, is now running
              val now = clock.now()
              Action.Update(
                taskState.copy(launchedTask = Some(
                  currentLaunched.copy(
                    status = currentLaunched.status.copy(
                      startedAt = Some(now),
                      status = Some(statusUpdate)
                    )
                  )
                ))
              )
            case _ =>
              updateTaskOnStateChange(taskState, currentLaunched, statusUpdate)
          }

        case None =>
          log.warn("Got update for task which wasn't launched yet: {}", statusUpdate)
          Action.Noop
      }
    }

    private[this] def updateTaskOnStateChange(
      taskState: Task, currentLaunch: Task.LaunchedTask, statusUpdate: TaskStatus): Action = {

      def updatedOnChange(currentStatus: TaskStatus): Option[Task] = {
        val healthy =
          statusUpdate.hasHealthy && (!currentStatus.hasHealthy || currentStatus.getHealthy != statusUpdate.getHealthy)
        val changed = healthy || currentStatus.getState != statusUpdate.getState
        if (changed) {
          Some(
            taskState.copy(
              launchedTask = Some(
                currentLaunch.copy(
                  status = currentLaunch.status.copy(status = Some(currentStatus))
                )
              )
            )
          )
        }
        else {
          log.info("currentStatus {}", currentStatus)
          log.info("update {}", statusUpdate)
          None
        }
      }

      val maybeUpdated = currentLaunch.status.status.flatMap(updatedOnChange(_))

      maybeUpdated match {
        case Some(updated) => Action.Update(updated)
        case None =>
          log.debug(s"Ignoring status update for ${taskState.taskId}. Status did not change.")
          Action.Noop
      }
    }
  }
}
/**
  * Processes durable operations on tasks by
  *
  * * storing the updated tasks in the task repository
  * * informing the taskTracker actor of the latest task state
  */
private[tracker] class TaskOpProcessorImpl(
    taskTrackerRef: ActorRef,
    repo: TaskRepository,
    statusUpdateActionResolver: StatusUpdateActionResolver) extends TaskOpProcessor {
  private[this] val log = LoggerFactory.getLogger(getClass)

  import TaskOpProcessor._

  override def process(op: Operation)(implicit ec: ExecutionContext): Future[Unit] = {
    op.action match {

      case Action.Update(task) =>
        // Used for a create or as a result from a UpdateStatus action.
        // The update is propagated to the taskTracker which in turn informs the sender about the success (see Ack).
        val marathonTask = TaskSerializer.marathonTask(task)
        repo.store(marathonTask).map { _ =>
          taskTrackerRef ! TaskTrackerActor.TaskUpdated(task, TaskTrackerActor.Ack(op.sender))
        }.recoverWith(tryToRecover(op)(expectedTaskState = Some(task)))
      case Action.Expunge =>
        // Used for task termination or as a result from a UpdateStatus action.
        // The expunge is propagated to the taskTracker which in turn informs the sender about the success (see Ack).
        repo.expunge(op.taskId.id).map { _ =>
          taskTrackerRef ! TaskTrackerActor.TaskRemoved(op.taskId, TaskTrackerActor.Ack(op.sender))
        }.recoverWith(tryToRecover(op)(expectedTaskState = None))

      case Action.UpdateStatus(status) =>
        statusUpdateActionResolver.resolve(op.taskId, status).flatMap { action: Action =>
          // Since this action is mapped to another action, we delegate the responsibility to inform
          // the sender to that other action.
          process(op.copy(action = action))
        }

      case Action.Noop =>
        // Used if a task status update does not result in any changes.
        // Since we did not change the task state, we inform the sender directly of the success of
        // the operation.
        op.sender ! (())
        Future.successful(())
      case Action.Fail(cause) =>
        // Used if a task status update for a non-existing task is processed.
        // Since we did not change the task state, we inform the sender directly of the failed operation.
        op.sender ! Status.Failure(cause)
        Future.successful(())
    }
  }

  /**
    * If we encounter failure, we try to reload the effected task to make sure that the taskTracker
    * is up-to-date. We signal failure to the sender if the state is not as expected.
    *
    * If reloading the tasks also fails, the operation does fail.
    *
    * This tries to isolated failures that only effect certain tasks, e.g. errors in the serialization logic
    * which are only triggered for a certain combination of fields.
    */
  private[this] def tryToRecover(
    op: Operation)(
      expectedTaskState: Option[Task])(
        implicit ec: ExecutionContext): PartialFunction[Throwable, Future[Unit]] = {

    case NonFatal(cause) =>
      def ack(actualTaskState: Option[MarathonTask]): TaskTrackerActor.Ack = {
        val msg = if (expectedTaskState.map(_.marathonTask) == actualTaskState) (()) else Status.Failure(cause)
        TaskTrackerActor.Ack(op.sender, msg)
      }

      log.warn(
        s"${op.taskId} of app [${op.taskId.appId}]: try to recover from failed ${op.action.toString}", cause
      )

      repo.task(op.taskId.id).map {
        case Some(task) =>
          val taskState = TaskSerializer.taskState(task)
          taskTrackerRef ! TaskTrackerActor.TaskUpdated(taskState, ack(Some(task)))
        case None =>
          taskTrackerRef ! TaskTrackerActor.TaskRemoved(op.taskId, ack(None))
      }.recover {
        case NonFatal(loadingFailure) =>
          log.warn(
            s"${op.taskId} of app [${op.taskId.appId}]: task reloading failed as well", loadingFailure
          )
          throw cause
      }
  }
}
